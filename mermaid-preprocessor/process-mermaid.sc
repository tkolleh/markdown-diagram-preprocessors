#!/bin/sh
# This wrapper finds scala-cli in common locations and executes the script
# It handles the "No such file or directory" error in GUI apps like Marked 2
#
# Add common install locations to PATH
export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

exec scala-cli --power shebang "$0" "$@"
!#
//> using scala "2.13.18"
//> using jvm "corretto:21"
//> using platform "jvm"
//> using dep "com.lihaoyi::mainargs:0.7.6"
//> using dep "com.lihaoyi::os-lib:0.11.3"

import mainargs.{arg, ParserForClass, Flag}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.{Try, Using}
import sys.process._

case class Config(
  @arg(short = 'i', doc = "Input markup file path (default: stdin)")
  inputFile: Option[String] = None,
  @arg(short = 'o', doc = "Output file path (default: stdout)")
  outputFile: Option[String] = None,
  @arg(short = 'f', doc = "Markup format: md (default) or adoc")
  format: String = "md",
  @arg(short = 'v', doc = "Verbose mode")
  verbose: Flag = Flag()
)

// A fence spec describes how to locate one kind of diagram block in one markup
// format. `detectRegex` is the rg multiline pattern (group 1 = inner content);
// `extractRegex` re-derives that content from the file text precisely.
final case class FenceSpec(label: String, detectRegex: String, extractRegex: String)

case class MermaidCodeBlock(
  content: String,
  lineNumber: Int,
  absoluteOffset: Int
)

// Markdown: ```mermaid ... ```
// AsciiDoc: [mermaid]\n----...----  and  [source,mermaid]\n----...----  (both forms)
def fenceSpecs(format: String): List[FenceSpec] = format match {
  case "adoc" => List(
    FenceSpec("[mermaid]",        """\[mermaid\]\n----\n((?s).*?)\n----""",          """(?s)\[mermaid\]\n----\n(.*?)\n----"""),
    FenceSpec("[source,mermaid]", """\[source,\s*mermaid\]\n----\n((?s).*?)\n----""", """(?s)\[source,\s*mermaid\]\n----\n(.*?)\n----""")
  )
  case _ => List(
    FenceSpec("```mermaid", """```mermaid\n((?s).*?)\n```""", """(?s)```mermaid\n(.*?)\n```""")
  )
}

def findMermaidBlocks(filePath: String, spec: FenceSpec, verbose: Boolean): List[MermaidCodeBlock] = {
  val cmd = Seq(
    "rg",
    "--multiline",
    "--json",
    spec.detectRegex,
    filePath
  )

  if (verbose) System.err.println(s"Running: ${cmd.mkString(" ")}")

  val output = new StringBuilder()

  val exitCode = Try {
    val result = cmd.!!
    output.append(result)
    0
  }.recover {
    // exit code 1 means no matches — not an error
    case _: RuntimeException => 1
  }.getOrElse(-1)

  if (exitCode < 0) {
    if (verbose) System.err.println(s"ripgrep error (exit $exitCode)")
    return List.empty
  }

  val blocks = output.toString.split("\n")
    .filter(_.trim.nonEmpty)
    .flatMap { line =>
      Try {
        // Manual JSON field extraction to avoid pulling in ujson
        if (!line.contains("\"type\":\"match\"")) None
        else {
          val lineNumMatch  = """"line_number":(\d+)""".r.findFirstMatchIn(line)
          val absOffMatch   = """"absolute_offset":(\d+)""".r.findFirstMatchIn(line)

          for {
            lineNum <- lineNumMatch.map(_.group(1).toInt)
            absOff  <- absOffMatch.map(_.group(1).toInt)
          } yield {
            // Read the actual file content to extract the mermaid block precisely
            // ripgrep JSON-encodes the text field; read from file directly instead
            val fileContent = os.read(os.Path(filePath))
            val blockPattern = spec.extractRegex.r
            val allBlocks = blockPattern.findAllMatchIn(fileContent).toList

            // Match by line number: find the block whose start offset is nearest to absOff
            val matchedContent = allBlocks.minByOption { m =>
              math.abs(fileContent.take(m.start).count(_ == '\n') + 1 - lineNum)
            }.map(_.group(1)).getOrElse("")

            MermaidCodeBlock(matchedContent, lineNum, absOff)
          }
        }
      }.toOption.flatten
    }
    .toList

  if (verbose) System.err.println(s"Found ${blocks.length} ${spec.label} blocks")
  blocks
}

def sanitizeSvg(svgContent: String): String = {
  // Strip XML declaration — invalid when SVG is embedded inline in HTML
  // Strip <script> tags — XSS risk in rendered output; mmdc sometimes adds them
  val noXmlDecl  = svgContent.replaceAll("""<\?xml[^?]*\?>""", "").replaceAll("^\\s+", "")
  val noScripts  = noXmlDecl.replaceAll("""(?si)<script\b[^>]*>.*?</script>""", "")
  noScripts.trim
}

def renderMermaidToString(code: String, verbose: Boolean = false): String = {
  val tmpDir    = Files.createTempDirectory("mermaid-").toFile
  val inputFile = new java.io.File(tmpDir, "diagram.mmd")
  val outputFile = new java.io.File(tmpDir, "diagram.svg")

  try {
    os.write(os.Path(inputFile.getAbsolutePath), code)

    val cmd = Seq(
      "mmdc",
      "-i", inputFile.getAbsolutePath,
      "-o", outputFile.getAbsolutePath,
      "-t", "neutral",
      "-b", "transparent",
      "--quiet"
    )

    if (verbose) System.err.println(s"Running: ${cmd.mkString(" ")}")

    val stderrBuf = new StringBuilder()
    val logger = ProcessLogger(
      out => if (verbose) System.err.println(s"mmdc: $out"),
      err => stderrBuf.append(err).append("\n")
    )

    val exitCode = Process(cmd).!(logger)

    if (verbose) System.err.println(s"mmdc exit code: $exitCode")

    if (exitCode == 0 && outputFile.exists()) {
      val rawSvg = os.read(os.Path(outputFile.getAbsolutePath))
      sanitizeSvg(rawSvg)
    } else {
      val errorMsg = stderrBuf.toString.trim
      if (verbose) System.err.println(s"mmdc error: $errorMsg")
      s"""<div style="border: 1px solid red; padding: 10px; color: #a00;"><strong>Mermaid Error:</strong><pre>$errorMsg</pre></div>"""
    }
  } finally {
    // Clean up temp files regardless of success or failure
    inputFile.delete()
    outputFile.delete()
    tmpDir.delete()
  }
}

// ── Entry point ──────────────────────────────────────────────────────────────

val config = ParserForClass[Config].constructOrExit(args.toSeq)

// ripgrep needs a real file path; materialise stdin to a temp file if needed
val inputPath = config.inputFile match {
  case Some(path) =>
    if (config.verbose.value) System.err.println(s"Reading from file: $path")
    path
  case None =>
    if (config.verbose.value) System.err.println("Reading from stdin, creating temp file")
    val suffix   = if (config.format == "adoc") ".adoc" else ".md"
    val tempFile = Files.createTempFile("mermaid-markup-", suffix).toFile
    tempFile.deleteOnExit()
    Using(scala.io.Source.fromInputStream(System.in)) { source =>
      os.write.over(os.Path(tempFile.getAbsolutePath), source.mkString)
    }
    tempFile.getAbsolutePath
}

val input         = os.read(os.Path(inputPath))
val specs         = fenceSpecs(config.format)
val mermaidBlocks = specs.flatMap(spec => findMermaidBlocks(inputPath, spec, config.verbose.value))

// Replace in reverse offset order so earlier positions stay valid as we mutate
val processed = mermaidBlocks.sortBy(-_.absoluteOffset).foldLeft(input) { (text, block) =>
  if (config.verbose.value)
    System.err.println(s"Processing mermaid block at line ${block.lineNumber} (${block.content.length} chars)")

  val svgContent = renderMermaidToString(block.content, config.verbose.value)

  val replacement =
    s"""<div class="mermaid-diagram" style="margin: 2em 0; display: flex; justify-content: center;">
       |$svgContent
       |</div>""".stripMargin

  // Reconstruct the full source block (with its fence) to replace it exactly.
  val quoted = java.util.regex.Pattern.quote(block.content)
  val blockPattern =
    if (config.format == "adoc") s"""\\[(?:source,\\s*)?mermaid\\]\n----\n$quoted\n----"""
    else s"```mermaid\n$quoted\n```"
  text.replaceFirst(blockPattern, java.util.regex.Matcher.quoteReplacement(replacement))
}

config.outputFile match {
  case Some(path) =>
    if (config.verbose.value) System.err.println(s"Writing to file: $path")
    os.write.over(os.Path(path, os.pwd), processed)
  case None =>
    print(processed)
}
