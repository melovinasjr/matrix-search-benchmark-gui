# Matrix Search Benchmark GUI

Coursework project for Data Structures.

This project compares multiple search strategies on a generated matrix through a Java Swing GUI. It benchmarks sequential search, binary search over a cached sorted array, and hash-based lookup.

## Coursework Note

Built as an academic project to compare search strategies and observe how preprocessing changes lookup performance.

## Features

- Java Swing interface for matrix generation and search
- Sequential search
- Binary search with cached sorted data
- HashMap lookup with cached value positions
- Runtime benchmarking over repeated runs
- Optional C performance file for lower-level comparison

## Tech Stack

- Java
- Swing
- C
- Sequential search
- Binary search
- Hash table lookup

## Run

```bash
javac MatrixSearchGUI.java
java MatrixSearchGUI
```

## What To Look For

- Sequential search scans the matrix directly.
- Binary search uses a cached sorted 1D representation.
- Hash search uses a cached value-to-position map.
- The GUI logs timing results across repeated runs.
