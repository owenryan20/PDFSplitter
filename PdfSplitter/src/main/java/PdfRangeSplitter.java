import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PageExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PdfRangeSplitter — single-class CLI tool to split a PDF into multiple PDFs based on inclusive 1-based page ranges.
 *
 * Requirements satisfied:
 * - Java 17+, Maven, PDFBox 3.x (only external dependency)
 * - Single class with main(String[] args)
 * - Options:
 *   - -i, --input <path>      (required)
 *   - -r, --ranges "<spec>"   (required) e.g. "1-3, 10-12"
 *   - -o, --out-dir <path>    (optional; defaults to input's directory)
 *   - -p, --password <string> (optional; for encrypted PDFs)
 *   - -h, --help              (show usage)
 *
 * Behavior:
 * - Page numbering is 1-based and inclusive.
 * - Accepts spaces around commas and dashes.
 * - Validates ranges: start >= 1, end >= start, end <= total pages.
 * - Rejects overlaps and duplicate ranges with a clear error.
 * - Outputs files named <inputBase>_<start>-<end>.pdf into chosen output directory.
 * - Uses PDFBox PageExtractor to minimize memory and properly copy resources.
 * - Prints concise summary or helpful errors; exits non-zero on failure.
 */
public class PdfRangeSplitter {

    private static final Pattern RANGE_PATTERN = Pattern.compile("\\s*(\\d+)\\s*-\\s*(\\d+)\\s*");

    private static class Cli {
        String inputPath;
        String rangeSpec;
        String outDir;     // optional
        String password;   // optional
        boolean help = false;
    }

    private static class Range implements Comparable<Range> {
        final int start;
        final int end;

        Range(int start, int end) {
            this.start = start;
            this.end = end;
        }

        int length() { return end - start + 1; }

        @Override
        public int compareTo(Range o) {
            int c = Integer.compare(this.start, o.start);
            if (c != 0) return c;
            return Integer.compare(this.end, o.end);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Range)) return false;
            Range r = (Range) obj;
            return this.start == r.start && this.end == r.end;
        }

        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }

        @Override
        public String toString() {
            return start + "-" + end;
        }
    }

    public static void main(String[] args) {
        Cli cli;
        try {
            cli = parseArgs(args);
        } catch (IllegalArgumentException iae) {
            System.err.println("Error: " + iae.getMessage());
            printUsage(System.err);
            System.exit(2);
            return;
        }

        if (cli.help) {
            printUsage(System.out);
            return;
        }
        if (cli.inputPath == null || cli.rangeSpec == null) {
            System.err.println("Error: --input and --ranges are required.");
            printUsage(System.err);
            System.exit(2);
            return;
        }

        Path input = Paths.get(cli.inputPath);
        if (!Files.exists(input)) {
            fail("Input PDF does not exist: " + input, 2);
        }
        if (!Files.isReadable(input)) {
            fail("Input PDF is not readable: " + input, 2);
        }

        // Determine output directory (default: same as input)
        Path outDir = (cli.outDir != null && !cli.outDir.isBlank())
                ? Paths.get(cli.outDir)
                : (input.getParent() != null ? input.getParent() : Paths.get("."));
        try {
            Files.createDirectories(outDir);
        } catch (IOException ioe) {
            fail("Failed to create output directory '" + outDir + "': " + ioe.getMessage(), 2);
        }

        // Open document with/without password
        try (PDDocument src = open(input, cli.password)) {
            final int totalPages = src.getNumberOfPages();
            if (totalPages <= 0) {
                fail("Input PDF appears to have no pages.", 1);
            }

            // Parse + validate ranges against total pages
            List<Range> ranges = parseRanges(cli.rangeSpec, totalPages);

            // Detect duplicates and overlaps
            validateNoDuplicates(ranges);
            validateNoOverlaps(ranges);

            // Base file name (without extension)
            String baseName = stripExtension(input.getFileName().toString());

            // Process in the order provided by the user
            List<Path> created = new ArrayList<>();
            for (Range r : ranges) {
                String outFileName = baseName + "_" + r.start + "-" + r.end + ".pdf";
                Path outPath = outDir.resolve(outFileName);

                try (PDDocument part = new PageExtractor(src, r.start, r.end).extract()) {
                    part.save(outPath.toFile());
                } catch (IOException ioe) {
                    fail("Failed to write range " + r + " to '" + outPath + "': " + ioe.getMessage(), 1);
                }
                created.add(outPath);
            }

            // Summary
            System.out.println("OK: Split complete.");
            System.out.println("Source: " + input.toAbsolutePath() + " (" + totalPages + " pages)");
            System.out.println("Output directory: " + outDir.toAbsolutePath());
            for (int i = 0; i < ranges.size(); i++) {
                Range r = ranges.get(i);
                Path p = created.get(i);
                System.out.println(" - " + p.getFileName() + "  [" + r.start + "-" + r.end + ", " + r.length() + " pages]");
            }

        } catch (InvalidPasswordException ipe) {
            String hint = (cli.password == null)
                    ? " (provide a password with -p/--password)"
                    : " (check that the password is correct)";
            fail("Encrypted PDF: cannot open" + hint, 3);
        } catch (IOException ioe) {
            fail("I/O error while processing PDF: " + ioe.getMessage(), 1);
        }
    }

    // Attempt to open with provided password if any; otherwise try no password, then empty-string password.
    private static PDDocument open(Path pdf, String password) throws IOException {
        File f = pdf.toFile();
        if (password != null) {
            return Loader.loadPDF(f, password);
        }
        try {
            return Loader.loadPDF(f);
        } catch (InvalidPasswordException e) {
            // Some PDFs use an empty string password
            return Loader.loadPDF(f, "");
        }
    }

    private static Cli parseArgs(String[] args) {
        Cli cli = new Cli();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    cli.help = true;
                    return cli;
                case "-i":
                case "--input":
                    cli.inputPath = requireValue(a, args, ++i);
                    break;
                case "-r":
                case "--ranges":
                    cli.rangeSpec = requireValue(a, args, ++i);
                    break;
                case "-o":
                case "--out-dir":
                    cli.outDir = requireValue(a, args, ++i);
                    break;
                case "-p":
                case "--password":
                    cli.password = requireValue(a, args, ++i);
                    break;
                default:
                    if (a.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown option: " + a);
                    } else {
                        throw new IllegalArgumentException("Unexpected argument: " + a);
                    }
            }
        }
        return cli;
    }

    private static String requireValue(String opt, String[] args, int index) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + opt);
        }
        return args[index];
    }

    private static List<Range> parseRanges(String spec, int totalPages) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Range spec must not be empty.");
        }
        String[] tokens = spec.split("\\s*,\\s*");
        List<Range> ranges = new ArrayList<>();
        for (String t : tokens) {
            if (t.isBlank()) continue; // ignore accidental extra commas
            Matcher m = RANGE_PATTERN.matcher(t);
            if (!m.matches()) {
                throw new IllegalArgumentException("Invalid range token '" + t + "'. Expected form 'start-end'.");
            }
            int start = Integer.parseInt(m.group(1));
            int end = Integer.parseInt(m.group(2));
            if (start < 1) {
                throw new IllegalArgumentException("Invalid range '" + t + "': start must be >= 1.");
            }
            if (end < start) {
                throw new IllegalArgumentException("Invalid range '" + t + "': end must be >= start.");
            }
            if (end > totalPages) {
                throw new IllegalArgumentException("Invalid range '" + t + "': end (" + end + ") exceeds total pages (" + totalPages + ").");
            }
            ranges.add(new Range(start, end));
        }
        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("No valid ranges parsed from: " + spec);
        }
        return ranges;
    }

    private static void validateNoDuplicates(List<Range> ranges) {
        Set<Range> seen = new HashSet<>();
        for (Range r : ranges) {
            if (!seen.add(r)) {
                throw new IllegalArgumentException("Duplicate range detected: " + r + ". Duplicates are not allowed.");
            }
        }
    }

    private static void validateNoOverlaps(List<Range> ranges) {
        List<Range> sorted = new ArrayList<>(ranges);
        Collections.sort(sorted);
        for (int i = 1; i < sorted.size(); i++) {
            Range prev = sorted.get(i - 1);
            Range cur = sorted.get(i);
            if (cur.start <= prev.end) {
                throw new IllegalArgumentException(
                        "Overlapping ranges detected: " + prev + " overlaps " + cur + ". Overlaps are not allowed.");
            }
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0) ? filename.substring(0, dot) : filename;
    }

    private static void printUsage(java.io.PrintStream out) {
        out.println("PdfRangeSplitter - Split a PDF into multiple PDFs by inclusive 1-based page ranges.");
        out.println();
        out.println("Usage:");
        out.println("  java -jar pdf-range-splitter.jar -i <input.pdf> -r \"<spec>\" [-o <outDir>] [-p <password>]");
        out.println();
        out.println("Options:");
        out.println("  -i, --input <path>       Input PDF path (required).");
        out.println("  -r, --ranges \"<spec>\"    Comma-separated inclusive ranges (1-based).");
        out.println("                           Accepts spaces around commas and dashes.");
        out.println("                           Example: \"1-3, 10-12, 50-50\"");
        out.println("  -o, --out-dir <path>     Output directory (default: input's directory).");
        out.println("  -p, --password <string>  Password for encrypted PDFs (optional).");
        out.println("  -h, --help               Show this help and exit.");
        out.println();
        out.println("Rules:");
        out.println("  * Ranges must be within the document: start >= 1, end >= start, end <= total pages.");
        out.println("  * Overlapping or duplicate ranges are rejected with a clear error.");
        out.println("  * Output files are named <inputBase>_<start>-<end>.pdf");
        out.println();
        out.println("Examples:");
        out.println("  java -jar target/pdf-range-splitter.jar \\");
        out.println("    --input /path/to/input.pdf --ranges \"158-171, 172-184, 185-195\"");
        out.println();
        out.println("  java -jar target/pdf-range-splitter.jar -i input.pdf -r \"1-3, 10-12\" -o out/");
        out.println();
        out.println("  java -jar target/pdf-range-splitter.jar -i secret.pdf -r \"5-10\" -p \"hunter2\"");
        out.println();
    }

    private static void fail(String msg, int code) {
        System.err.println("Error: " + msg);
        System.exit(code);
    }
}
		