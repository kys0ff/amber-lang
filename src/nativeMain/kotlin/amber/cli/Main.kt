package amber.cli

fun main(args: Array<String>) {
    val parser = CliParser()
    val config = parser.parse(args)
    
    val dashDashIndex = args.indexOf("--")
    val scriptArgs = if (dashDashIndex != -1) args.slice(dashDashIndex + 1 until args.size) else emptyList()
    
    val runner = CompilerRunner(config)
    runner.run(scriptArgs)
}
