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
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
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

final case class D2CodeBlock(content: String, lineNumber: Int, absoluteOffset: Int)

// Markdown: ```d2 ... ```
// AsciiDoc: [d2]\n----...----  and  [source,d2]\n----...----  (both forms)
def fenceSpecs(format: String): List[FenceSpec] = format match {
  case "adoc" => List(
    FenceSpec("[d2]",        """\[d2\]\n----\n((?s).*?)\n----""",          """(?s)\[d2\]\n----\n(.*?)\n----"""),
    FenceSpec("[source,d2]", """\[source,\s*d2\]\n----\n((?s).*?)\n----""", """(?s)\[source,\s*d2\]\n----\n(.*?)\n----""")
  )
  case _ => List(
    FenceSpec("```d2", """```d2\n((?s).*?)\n```""", """(?s)```d2\n(.*?)\n```""")
  )
}

def findBlocks(filePath: String, spec: FenceSpec, verbose: Boolean): List[D2CodeBlock] = {
  val cmd = Seq("rg", "--multiline", "--json", spec.detectRegex, filePath)

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

  val extractPattern = spec.extractRegex.r

  val blocks = output.toString.split("\n")
    .filter(_.trim.nonEmpty)
    .flatMap { line =>
      Try {
        // Manual JSON field extraction to avoid pulling in ujson
        if (!line.contains("\"type\":\"match\"")) None
        else {
          val lineNumMatch = """"line_number":(\d+)""".r.findFirstMatchIn(line)
          val absOffMatch  = """"absolute_offset":(\d+)""".r.findFirstMatchIn(line)

          for {
            lineNum <- lineNumMatch.map(_.group(1).toInt)
            absOff  <- absOffMatch.map(_.group(1).toInt)
          } yield {
            // ripgrep JSON-encodes the text field; read from file directly instead
            // and match by line number to extract the exact block content.
            val fileContent = os.read(os.Path(filePath))
            val allBlocks   = extractPattern.findAllMatchIn(fileContent).toList

            val matchedContent = allBlocks.minByOption { m =>
              math.abs(fileContent.take(m.start).count(_ == '\n') + 1 - lineNum)
            }.map(_.group(1)).getOrElse("")

            D2CodeBlock(matchedContent, lineNum, absOff)
          }
        }
      }.toOption.flatten
    }
    .toList

  if (verbose) System.err.println(s"Found ${blocks.length} ${spec.label} blocks")
  blocks
}

def renderD2ToString(code: String, verbose: Boolean = false): String = {
  val stdoutBytes = new ByteArrayOutputStream()
  val stderrBytes = new ByteArrayOutputStream()

  // Using a Managed approach for the Input Stream
  val result = Using(new ByteArrayInputStream(code.getBytes(StandardCharsets.UTF_8))) { is =>
    // 'd2 - -' reads from stdin, writes to stdout
    val cmd = Seq("d2", "-", "-")
    val io = new ProcessIO(
      in => {
        val buffer = new Array[Byte](4096)
        var bytesRead = 0
        while ({bytesRead = is.read(buffer); bytesRead != -1}) {
          in.write(buffer, 0, bytesRead)
        }
        in.close()
      },
      out => {
        val buffer = new Array[Byte](4096)
        var bytesRead = 0
        while ({bytesRead = out.read(buffer); bytesRead != -1}) {
          stdoutBytes.write(buffer, 0, bytesRead)
        }
        out.close()
      },
      err => {
        val buffer = new Array[Byte](4096)
        var bytesRead = 0
        while ({bytesRead = err.read(buffer); bytesRead != -1}) {
          stderrBytes.write(buffer, 0, bytesRead)
        }
        err.close()
      }
    )

    val process = Process(cmd).run(io)
    val exitCode = process.exitValue()

    if (verbose) System.err.println(s"D2 process exit code: $exitCode")

    if (exitCode == 0) {
      new String(stdoutBytes.toByteArray(), StandardCharsets.UTF_8)
    } else {
      val errorMsg = new String(stderrBytes.toByteArray(), StandardCharsets.UTF_8)
      if (verbose) System.err.println(s"D2 Error: $errorMsg")
      s"<div style='border: 1px solid red; padding: 10px; color: #a00;'><strong>D2 Error:</strong><pre>$errorMsg</pre></div>"
    }
  }

  // Ensure we handle the Try result and return the string
  result.getOrElse("<p>Error: Failed to initialize D2 rendering process.</p>")
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
    val tempFile = Files.createTempFile("d2-markup-", suffix).toFile
    tempFile.deleteOnExit()
    Using(scala.io.Source.fromInputStream(System.in)) { source =>
      os.write.over(os.Path(tempFile.getAbsolutePath), source.mkString)
    }
    tempFile.getAbsolutePath
}

val input  = os.read(os.Path(inputPath))
val specs  = fenceSpecs(config.format)
val blocks = specs.flatMap(spec => findBlocks(inputPath, spec, config.verbose.value))

// Replace in reverse offset order so earlier positions stay valid as we mutate
val processed = blocks.sortBy(-_.absoluteOffset).foldLeft(input) { (text, block) =>
  if (config.verbose.value)
    System.err.println(s"Processing D2 block at line ${block.lineNumber} (${block.content.length} chars)")

  val svgContent = renderD2ToString(block.content, config.verbose.value)

  val core =
    s"""<div class="d2-diagram" style="margin: 2em 0; display: flex; justify-content: center;">
       |$svgContent
       |</div>""".stripMargin

  // AsciiDoc escapes inline HTML; a passthrough block emits it verbatim. Markdown needs none.
  val replacement =
    if (config.format == "adoc") s"++++\n$core\n++++" else core

  // Reconstruct the full source block (with its fence) to replace it exactly.
  val quoted = java.util.regex.Pattern.quote(block.content)
  val blockPattern =
    if (config.format == "adoc") s"""\\[(?:source,\\s*)?d2\\]\n----\n$quoted\n----"""
    else s"""```d2\n$quoted\n```"""
  text.replaceFirst(blockPattern, java.util.regex.Matcher.quoteReplacement(replacement))
}

config.outputFile match {
  case Some(path) =>
    if (config.verbose.value) System.err.println(s"Writing to file: $path")
    os.write.over(os.Path(path, os.pwd), processed)
  case None =>
    print(processed)
}
