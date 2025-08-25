# PDF Splitter

A lightweight Java CLI tool to split a PDF into multiple PDFs based on inclusive **1-based page ranges**.  

Built with **Java 17+**, **Maven**, and **Apache PDFBox 3.x**.

---

## ✨ Features

- Split a PDF into multiple smaller PDFs by specifying page ranges.
- 1-based inclusive ranges (`1-3` extracts pages 1, 2, and 3).
- Multiple ranges in one run: `"10-15, 20-25, 30-40"`.
- Validates ranges:  
  - start ≥ 1  
  - end ≥ start  
  - end ≤ total pages  
  - no overlaps or duplicates allowed
- Works with password-protected PDFs (if password provided).
- Outputs files named `<inputBase>_<start>-<end>.pdf`.
- Graceful errors with clear messages.

---

## ⚙️ Requirements

- **Java 17+**
- **Maven 3.8+**

---

## 📦 Build

```bash
mvn -q clean package
```

This produces a shaded runnable JAR:

```
target/pdf-splitter.jar
```

---

## 🚀 Usage

### Basic
```bash
java -jar target/pdf-splitter.jar   --input /path/to/input.pdf   --ranges "158-171, 172-184, 185-195"
```

### With output directory
```bash
java -jar target/pdf-splitter.jar   -i input.pdf -r "1-3, 10-12" -o out/
```

### With password
```bash
java -jar target/pdf-splitter.jar   -i secret.pdf -r "5-10" -p "hunter2"
```

---

## 📑 CLI Options

| Option                  | Description                                                   | Required |
|--------------------------|---------------------------------------------------------------|----------|
| `-i, --input <path>`    | Input PDF file path                                           | ✅       |
| `-r, --ranges "<spec>"` | Comma-separated inclusive ranges (e.g. `"1-3, 5-7"`)          | ✅       |
| `-o, --out-dir <path>`  | Output directory (default: same dir as input)                 | ❌       |
| `-p, --password <str>`  | Password for encrypted PDFs                                   | ❌       |
| `-h, --help`            | Show usage text                                               | ❌       |

---

## 📂 Output Examples

Given `input.pdf` with ≥ 195 pages and:

```bash
--ranges "158-171, 172-184, 185-195"
```

It creates:
```
input_158-171.pdf
input_172-184.pdf
input_185-195.pdf
```

---

## ❗ Error Handling

- Invalid ranges (e.g., `0-3`, `20-10`, `999-1001`) → clear error, exit non-zero.
- Duplicate/overlapping ranges → clear error, exit non-zero.
- Encrypted file without correct password → error, exit non-zero.
