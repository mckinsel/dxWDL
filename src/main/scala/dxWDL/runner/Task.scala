/** Run a wdl task.

In the example below, we want to run the Add task in a dx:applet.

task Add {
    Int a
    Int b

    command {
        echo $((a + b))
    }
    output {
        Int sum = read_int(stdout())
    }
}

  */

package dxWDL.runner

import com.dnanexus.{DXJob}
import common.validation.Validation._
import dxWDL._
import java.nio.file.{Path, Paths}
import scala.collection.mutable.HashMap
import spray.json._
import wdl.{Declaration, DeclarationInterface, WdlExpression, WdlTask}
import wdl.types.WdlFlavoredWomType
import wom.InstantiatedCommand
import wom.values._
import wom.types._

private [dxWDL] object TaskSerialization {
    // Serialization of a WDL value to JSON
    private def wdlToJSON(t:WomType, w:WomValue) : JsValue = {
        (t, w)  match {
            // Base case: primitive types.
            // Files are encoded as their full path.
            case (WomBooleanType, WomBoolean(b)) => JsBoolean(b)
            case (WomIntegerType, WomInteger(n)) => JsNumber(n)
            case (WomFloatType, WomFloat(x)) => JsNumber(x)
            case (WomStringType, WomString(s)) => JsString(s)
            case (WomStringType, WomSingleFile(path)) => JsString(path)
            case (WomFileType, WomSingleFile(path)) => JsString(path)
            case (WomFileType, WomString(path)) => JsString(path)

            // arrays
            // Base case: empty array
            case (_, WomArray(_, ar)) if ar.length == 0 =>
                JsArray(Vector.empty)

            // Non empty array
            case (WomArrayType(t), WomArray(_, elems)) =>
                val jsVals = elems.map(e => wdlToJSON(t, e))
                JsArray(jsVals.toVector)

            // Maps. These are projections from a key to value, where
            // the key and value types are statically known.
            //
            // keys are strings, we can use JSON objects
            case (WomMapType(WomStringType, valueType), WomMap(_, m)) =>
                JsObject(m.map{
                         case (WomString(k), v) =>
                             k -> wdlToJSON(valueType, v)
                         case (k,_) =>
                             throw new Exception(s"key ${k.toWomString} should be a WomStringType")
                    }.toMap)

            // general case, the keys are not strings.
            case (WomMapType(keyType, valueType), WomMap(_, m)) =>
                val keys:WomValue = WomArray(WomArrayType(keyType), m.keys.toVector)
                val kJs = wdlToJSON(keys.womType, keys)
                val values:WomValue = WomArray(WomArrayType(valueType), m.values.toVector)
                val vJs = wdlToJSON(values.womType, values)
                JsObject("keys" -> kJs, "values" -> vJs)

            // keys are strings, requiring no conversion. We do
            // need to carry the types are runtime.
            case (WomObjectType, WomObject(m: Map[String, WomValue])) =>
                JsObject(m.map{ case (k, v) =>
                             k -> JsObject(
                                 "type" -> JsString(v.womType.toDisplayString),
                                 "value" -> wdlToJSON(v.womType, v))
                         }.toMap)

            case (WomPairType(lType, rType), WomPair(l,r)) =>
                val lJs = wdlToJSON(lType, l)
                val rJs = wdlToJSON(rType, r)
                JsObject("left" -> lJs, "right" -> rJs)

            // Strip optional type
            case (WomOptionalType(t), WomOptionalValue(_,Some(w))) =>
                wdlToJSON(t, w)
            case (WomOptionalType(t), w) =>
                wdlToJSON(t, w)
            case (t, WomOptionalValue(_,Some(w))) =>
                wdlToJSON(t, w)

            // missing value
            case (_, WomOptionalValue(_,None)) => JsNull

            case (_,_) => throw new Exception(
                s"""|Unsupported combination type=(${t.toDisplayString},${t})
                    |value=(${w.toWomString}, ${w})"""
                    .stripMargin.replaceAll("\n", " "))
        }
    }

    private def wdlFromJSON(t:WomType, jsv:JsValue) : WomValue = {
        (t, jsv)  match {
            // base case: primitive types
            case (WomBooleanType, JsBoolean(b)) => WomBoolean(b.booleanValue)
            case (WomIntegerType, JsNumber(bnm)) => WomInteger(bnm.intValue)
            case (WomFloatType, JsNumber(bnm)) => WomFloat(bnm.doubleValue)
            case (WomStringType, JsString(s)) => WomString(s)
            case (WomFileType, JsString(path)) => WomSingleFile(path)

            // arrays
            case (WomArrayType(t), JsArray(vec)) =>
                WomArray(WomArrayType(t),
                         vec.map{ elem => wdlFromJSON(t, elem) })


            // maps with string keys
            case (WomMapType(WomStringType, valueType), JsObject(fields)) =>
                val m: Map[WomValue, WomValue] = fields.map {
                    case (k,v) =>
                        WomString(k) -> wdlFromJSON(valueType, v)
                }.toMap
                WomMap(WomMapType(WomStringType, valueType), m)

            // General maps. These are serialized as an object with a keys array and
            // a values array.
            case (WomMapType(keyType, valueType), JsObject(_)) =>
                jsv.asJsObject.getFields("keys", "values") match {
                    case Seq(JsArray(kJs), JsArray(vJs)) =>
                        val m = (kJs zip vJs).map{ case (k, v) =>
                            val kWom = wdlFromJSON(keyType, k)
                            val vWom = wdlFromJSON(valueType, v)
                            kWom -> vWom
                        }.toMap
                        WomMap(WomMapType(keyType, valueType), m)
                    case _ => throw new Exception(s"Malformed serialized map ${jsv}")
                }

            case (WomObjectType, JsObject(fields)) =>
                val m: Map[String, WomValue] = fields.map{ case (k,v) =>
                    val elem:WomValue =
                        v.asJsObject.getFields("type", "value") match {
                            case Seq(JsString(elemTypeStr), elemValue) =>
                                val elemType:WomType = WdlFlavoredWomType.fromDisplayString(elemTypeStr)
                                wdlFromJSON(elemType, elemValue)
                        }
                    k -> elem
                }.toMap
                WomObject(m)

            case (WomPairType(lType, rType), JsObject(_)) =>
                jsv.asJsObject.getFields("left", "right") match {
                    case Seq(lJs, rJs) =>
                        val left = wdlFromJSON(lType, lJs)
                        val right = wdlFromJSON(rType, rJs)
                        WomPair(left, right)
                    case _ => throw new Exception(s"Malformed serialized par ${jsv}")
                }

            case (WomOptionalType(t), JsNull) =>
                WomOptionalValue(t, None)
            case (WomOptionalType(t), _) =>
                WomOptionalValue(wdlFromJSON(t, jsv))

            case _ =>
                throw new AppInternalException(
                    s"Unsupported combination ${t.toDisplayString} ${jsv.prettyPrint}"
                )
        }
    }

    // serialization routines
    def toJSON(w:WomValue) : JsValue = {
        JsObject("wdlType" -> JsString(w.womType.toDisplayString),
                 "wdlValue" -> wdlToJSON(w.womType, w))
    }

    def fromJSON(jsv:JsValue) : WomValue = {
        jsv.asJsObject.getFields("wdlType", "wdlValue") match {
            case Seq(JsString(typeStr), wValue) =>
                val wdlType = WdlFlavoredWomType.fromDisplayString(typeStr)
                wdlFromJSON(wdlType, wValue)
            case other => throw new DeserializationException(s"WomValue unexpected ${other}")
        }
    }
}


case class Task(task:WdlTask,
                cef: CompilerErrorFormatter) {
    def getMetaDir() = {
        val metaDir = Utils.getMetaDirPath()
        Utils.safeMkdir(metaDir)
        metaDir
    }

    // serialize the task inputs to json, and then write to a file.
    private def writeEnvToDisk(env: Map[String, WomValue]) : Unit = {
        val m : Map[String, JsValue] = env.map{ case(varName, v) =>
            (varName, TaskSerialization.toJSON(v))
        }.toMap
        val buf = (JsObject(m)).prettyPrint
        Utils.writeFileContent(getMetaDir().resolve(Utils.RUNNER_TASK_ENV_FILE),
                               buf)
    }

    private def readEnvFromDisk() : Map[String, WomValue] = {
        val buf = Utils.readFileContent(getMetaDir().resolve(Utils.RUNNER_TASK_ENV_FILE))
        val json : JsValue = buf.parseJson
        val m = json match {
            case JsObject(m) => m
            case _ => throw new Exception("Malformed task declarations")
        }
        m.map { case (key, jsVal) =>
            key -> TaskSerialization.fromJSON(jsVal)
        }.toMap
    }

    // Figure out if a docker image is specified. If so, return it as a string.
    private def dockerImageEval(env: Map[String, WomValue]) : Option[String] = {
        def lookup(varName : String) : WomValue = {
            env.get(varName) match {
                case Some(x) => x
                case None => throw new AppInternalException(
                    s"No value found for variable ${varName}")
            }
        }
        def evalStringExpr(expr: WdlExpression) : String = {
            val v : WomValue = expr.evaluate(lookup, DxFunctions).get
            v match {
                case WomString(s) => s
                case _ => throw new AppInternalException(
                    s"docker is not a string expression ${v.toWomString}")
            }
        }
        // Figure out if docker is used. If so, it is specified by an
        // expression that requires evaluation.
        task.runtimeAttributes.attrs.get("docker") match {
            case None => None
            case Some(expr) => Some(evalStringExpr(expr))
        }
    }

    private def dockerImage(env: Map[String, WomValue]) : Option[String] = {
        val dImg = dockerImageEval(env)
        dImg match {
            case Some(url) if url.startsWith(Utils.DX_URL_PREFIX) =>
                // This is a record on the platform, created with
                // dx-docker. Describe it with an API call, and get
                // the docker image name.
                Utils.appletLog(s"looking up dx:url ${url}")
                val dxRecord = DxPath.lookupDxURLRecord(url)
                Utils.appletLog(s"Found record ${dxRecord}")
                val imageName = dxRecord.describe().getName
                Utils.appletLog(s"Image name is ${imageName}")
                Some(imageName)
            case _ => dImg
        }
    }

    // Each file marked "stream" is converted into a special fifo
    // file on the instance.
    //
    // Make a named pipe, and stream the file from the platform to the
    // pipe. Ensure pipes have different names, even if the
    // file-names are the same. Write the process ids of the download
    // jobs to stdout. The calling script will keep track of them,
    // and check for abnormal termination.
    //
    def mkfifo(wvl: WdlVarLinks) : (WomValue, String) = {
        val dxFile = WdlVarLinks.getDxFile(wvl)
        val p:Path = LocalDxFiles.get(dxFile) match {
            case None => throw new Exception(s"File ${dxFile} has not been localized yet")
            case Some(p) => p
        }
        val bashSnippet:String =
            s"""|mkfifo ${p}
                |dx cat ${dxFile.getId} > ${p} &
                |echo $$!
                |""".stripMargin
        (WomSingleFile(p.toString), bashSnippet)
    }

    private def handleStreamingFile(wvl: WdlVarLinks,
                                    wdlValue:WomValue) : (WomValue, String) = {
        wdlValue match {
            case WomSingleFile(_) if wvl.attrs.stream =>
                mkfifo(wvl)
            case WomOptionalValue(_,Some(WomSingleFile(_))) if wvl.attrs.stream =>
                mkfifo(wvl)
            case _ =>
                throw new Exception(s"Value is not a streaming file ${wvl} ${wdlValue}")
        }
    }

    // Write the core bash script into a file. In some cases, we
    // need to run some shell setup statements before and after this
    // script. Returns these as two strings (prolog, epilog).
    private def writeBashScript(env: Map[String, WomValue]) : Unit = {
        val metaDir = getMetaDir()
        val scriptPath = metaDir.resolve("script")
        val stdoutPath = metaDir.resolve("stdout")
        val stderrPath = metaDir.resolve("stderr")
        val rcPath = metaDir.resolve("rc")

        // instantiate the command
        val cmdEnv: Map[Declaration, WomValue] = env.map {
            case (varName, wdlValue) =>
                val decl = task.declarations.find(_.unqualifiedName == varName) match {
                    case Some(x) => x
                    case None => throw new Exception(
                        s"Cannot find declaration for variable ${varName}")
                }
                decl -> wdlValue
        }.toMap
        val womInstantiation = task.instantiateCommand(cmdEnv, DxFunctions)
        val InstantiatedCommand(shellCmd, _) = womInstantiation.toTry.get

        // This is based on Cromwell code from
        // [BackgroundAsyncJobExecutionActor.scala].  Generate a bash
        // script that captures standard output, and standard
        // error. We need to be careful to pipe stdout/stderr to the
        // parent stdout/stderr, and not lose the result code of the
        // shell command. Notes on bash magic symbols used here:
        //
        //  Symbol  Explanation
        //    >     redirect stdout
        //    2>    redirect stderr
        //    <     redirect stdin
        //
        val script =
            if (shellCmd.isEmpty) {
                s"""|#!/bin/bash
                    |echo 0 > ${rcPath}
                    |""".stripMargin.trim + "\n"
            } else {
                s"""|#!/bin/bash
                    |(
                    |    cd ${Utils.DX_HOME}
                    |    ${shellCmd}
                    |) \\
                    |  > >( tee ${stdoutPath} ) \\
                    |  2> >( tee ${stderrPath} >&2 )
                    |echo $$? > ${rcPath}
                    |""".stripMargin.trim + "\n"
            }
        Utils.appletLog(s"writing bash script to ${scriptPath}")
        Utils.writeFileContent(scriptPath, script)
    }

    private def writeDockerSubmitBashScript(env: Map[String, WomValue],
                                            imgName: String) : Unit = {
        // The user wants to use a docker container with the
        // image [imgName]. We implement this with dx-docker.
        // There may be corner cases where the image will run
        // into permission limitations due to security.
        //
        // Map the home directory into the container, so that
        // we can reach the result files, and upload them to
        // the platform.
        val DX_HOME = Utils.DX_HOME
        val dockerCmd = s"""|dx-docker run --entrypoint /bin/bash
                            |-v ${DX_HOME}:${DX_HOME}
                            |${imgName}
                            |$${HOME}/execution/meta/script""".stripMargin.replaceAll("\n", " ")
        val dockerRunPath = getMetaDir().resolve("script.submit")
        val dockerRunScript =
            s"""|#!/bin/bash -ex
                |${dockerCmd}""".stripMargin
        Utils.appletLog(s"writing docker run script to ${dockerRunPath}")
        Utils.writeFileContent(dockerRunPath, dockerRunScript)
        dockerRunPath.toFile.setExecutable(true)
    }

    // Calculate the input variables for the task, download the input files,
    // and build a shell script to run the command.
    def prolog(inputSpec: Map[String, Utils.DXIOParam],
               outputSpec: Map[String, Utils.DXIOParam],
               inputWvls: Map[String, WdlVarLinks]) : Map[String, JsValue] = {
        val ioMode =
            if (task.commandTemplateString.trim.isEmpty) {
                // The shell command is empty, there is no need to download the files.
                IOMode.Remote
            } else {
                // default: download all input files
                Utils.appletLog(s"Eagerly download input files")
                IOMode.Data
            }

        var bashSnippetVec = Vector.empty[String]
        val envInput = inputWvls.map{ case (key, wvl) =>
            val w:WomValue =
                if (wvl.attrs.stream) {
                    // streaming file, create a named fifo for it
                    val w:WomValue = WdlVarLinks.localize(wvl, IOMode.Stream)
                    val (wdlValueRewrite,bashSnippet) = handleStreamingFile(wvl, w)
                    bashSnippetVec = bashSnippetVec :+ bashSnippet
                    wdlValueRewrite
                } else  {
                    // regular file
                    WdlVarLinks.localize(wvl, ioMode)
                }
            key -> w
        }.toMap

        // evaluate the declarations, and localize any files if necessary
        val env: Map[String, WomValue] =
            Eval.evalDeclarations(task.declarations, envInput)
                .map{ case (decl, v) => decl.unqualifiedName -> v}.toMap
        val docker = dockerImage(env)

        // deal with files that need streaming
        if (bashSnippetVec.size > 0) {
            // set up all the named pipes
            val path = getMetaDir().resolve("setup_streams")
            Utils.appletLog(s"writing bash script for stream(s) set up to ${path}")
            val snippet = bashSnippetVec.mkString("\n")
            Utils.writeFileContent(path, snippet)
            path.toFile.setExecutable(true)
        }

        // Write shell script to a file. It will be executed by the dx-applet code
        writeBashScript(env)
        docker match {
            case Some(img) =>
                // write a script that launches the actual command inside a docker image.
                // Streamed files are set up before launching docker.
                writeDockerSubmitBashScript(env, img)
            case None => ()
        }

        // serialize the environment, so we don't have to calculate it again in
        // the epilog
        writeEnvToDisk(env)

        // Checkpoint the localized file tables
        LocalDxFiles.freeze()
        DxFunctions.freeze()

        Map.empty
    }

    def epilog(inputSpec: Map[String, Utils.DXIOParam],
               outputSpec: Map[String, Utils.DXIOParam],
               inputs: Map[String, WdlVarLinks]) : Map[String, JsValue] = {
        // Repopulate the localized file tables
        LocalDxFiles.unfreeze()
        DxFunctions.unfreeze()

        val env : Map[String, WomValue] = readEnvFromDisk()

        // evaluate the output declarations.
        val outputs: Map[DeclarationInterface, WomValue] = Eval.evalDeclarations(task.outputs, env)

        // Upload any output files to the platform.
        val wvlOutputs:Map[String, WdlVarLinks] = outputs.map{ case (decl, wdlValue) =>
            // The declaration type is sometimes more accurate than the type of the wdlValue
            val wvl = WdlVarLinks.importFromWDL(decl.womType,
                                                DeclAttrs.empty, wdlValue, IODirection.Upload)
            decl.unqualifiedName -> wvl
        }.toMap

        // convert the WDL values to JSON
        val outputFields:Map[String, JsValue] = wvlOutputs.map {
            case (key, wvl) => WdlVarLinks.genFields(wvl, key)
        }.toList.flatten.toMap
        outputFields
    }


    // Evaluate the runtime expressions, and figure out which instance type
    // this task requires.
    private def calcInstanceType(taskInputs: Map[String, WdlVarLinks],
                                 instanceTypeDB: InstanceTypeDB) : String = {
        // input variables that were already calculated
        val env = HashMap.empty[String, WomValue]
        def lookup(varName : String) : WomValue = {
            env.get(varName) match {
                case Some(x) => x
                case None =>
                    // value not evaluated yet, calculate and keep in cache
                    taskInputs.get(varName) match {
                        case Some(wvl) =>
                            env(varName) = WdlVarLinks.eval(wvl, IOMode.Data, IODirection.Download)
                            env(varName)
                        case None => throw new UnboundVariableException(varName)
                    }
            }
        }
        def evalAttr(attrName: String) : Option[WomValue] = {
            task.runtimeAttributes.attrs.get(attrName) match {
                case None => None
                case Some(expr) =>
                    Some(expr.evaluate(lookup, DxFunctions).get)
            }
        }

        val dxInstaceType = evalAttr(Utils.DX_INSTANCE_TYPE_ATTR)
        val memory = evalAttr("memory")
        val diskSpace = evalAttr("disks")
        val cores = evalAttr("cpu")
        val iTypeRaw = InstanceTypeDB.parse(dxInstaceType, memory, diskSpace, cores)
        val iType = instanceTypeDB.apply(iTypeRaw)
        Utils.appletLog(s"""|calcInstanceType memory=${memory} disk=${diskSpace}
                            |cores=${cores} instancetype=${iType}"""
                            .stripMargin.replaceAll("\n", " "))
        iType
    }

    private def relaunchBuildInputs(inputWvls: Map[String, WdlVarLinks]) : JsValue = {
        val inputs:Map[String,JsValue] = inputWvls.foldLeft(Map.empty[String, JsValue]) {
            case (accu, (varName, wvl)) =>
                val fields = WdlVarLinks.genFields(wvl, varName)
                accu ++ fields.toMap
        }
        JsObject(inputs.toMap)
    }

    /** The runtime attributes need to be calculated at runtime. Evaluate them,
      *  determine the instance type [xxxx], and relaunch the job on [xxxx]
      */
    def relaunch(inputSpec: Map[String, Utils.DXIOParam],
                 outputSpec: Map[String, Utils.DXIOParam],
                 inputWvls: Map[String, WdlVarLinks]) : Map[String, JsValue] = {
        // Figure out the available instance types, and their prices,
        // by reading the file
        val dbRaw = Utils.readFileContent(Paths.get("/" + Utils.INSTANCE_TYPE_DB_FILENAME))
        val instanceTypeDB = dbRaw.parseJson.convertTo[InstanceTypeDB]

        // evaluate the runtime attributes
        // determine the instance type
        val instanceType:String = calcInstanceType(inputWvls, instanceTypeDB)

        // relaunch the applet on the correct instance type
        val inputs = relaunchBuildInputs(inputWvls)

        // Run a sub-job with the "body" entry point, and the required instance type
        val dxSubJob : DXJob = Utils.runSubJob("body", Some(instanceType), inputs, Vector.empty)

        // Return promises (JBORs) for all the outputs. Since the signature of the sub-job
        // is exactly the same as the parent, we can immediately exit the parent job.
        val outputs: Map[String, JsValue] = task.outputs.map { tso =>
            val wvl = WdlVarLinks(tso.womType,
                                  DeclAttrs.empty,
                                  DxlExec(dxSubJob, tso.unqualifiedName))
            WdlVarLinks.genFields(wvl, tso.unqualifiedName)
        }.flatten.toMap
        outputs
    }
}
