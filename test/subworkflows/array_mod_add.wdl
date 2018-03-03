import "library_modulo_math.wdl" as mod

workflow array_mod_add {
    Int n
    Int a

    scatter (i in range(n)) {
        call mod.add_modulo as add {
            input: n=n, a=a, b=i
        }
    }
    call bug_nop {
        input: x=n
    }
    output {
        Array[Int] result = add.result
    }
}

task bug_nop {
    Int x

    command {
        echo "Hello, world!"
    }
}
