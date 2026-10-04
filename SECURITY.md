# Security Policy

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](https://github.com/herakles-dev/nightjar/security/advisories/new)
rather than a public issue. Expect an acknowledgment within a few days.

## Scope

nightjar is an offline research and education app. It requests only the
`RECORD_AUDIO` permission and has no network access, so the relevant surface is
parsing of untrusted images and audio (the incoming-payload pipeline) and the
correctness of the steganography and detection code. Reports in those areas are
especially welcome.

## Supported versions

Only the latest release receives fixes.
