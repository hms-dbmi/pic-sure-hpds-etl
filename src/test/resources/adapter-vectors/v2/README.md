# v2: migration-style rows

One study (`phs123456.c1`) as the migration's split writes it, copied verbatim from the legacy columns: a header line at the top of both
files, unquoted fields, `\`-delimited paths, ISO timestamps (an instant, a date and a space-separated date-time, converted to epoch
milliseconds), a `0` timestamp (written empty), a quoted comma and quoted quotes, a blank line, a patient with no consent (its global row
is dropped), and four rows the adapter must reject: `nan` as the numeric value (`NUMERIC_NVAL`), a quoted field spanning two lines
(`EMBEDDED_LINE_BREAK`), an unparseable timestamp (`BAD_TIMESTAMP`) and a path that is not wrapped in delimiters (`NON_CANONICAL_PATH`).
Run with `--max-reject-fraction=0.5`. Paths are written exactly as read. All data is synthetic.
