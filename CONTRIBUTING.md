# Contributing to Token Flow

Thanks for your interest in contributing! This document explains how to get set up and what we expect from contributions.

## Code of Conduct

This project follows the [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you agree to uphold it.

## Getting Started

### Prerequisites

- JDK 21
- IntelliJ IDEA 2024.2+ (Community or Ultimate)
- Git

### Build & Run

```bash
git clone https://github.com/robinlopez/token-flow.git
cd token-flow
./gradlew runIde
```

This launches a sandboxed IntelliJ instance with the plugin installed.

### Run Tests

```bash
./gradlew test
```

## How to Contribute

### Reporting Bugs

Open a [GitHub issue](https://github.com/robinlopez/token-flow/issues) with:

- IntelliJ version and OS
- Plugin version
- Steps to reproduce
- Expected vs actual behavior
- Logs or screenshots if relevant

### Suggesting Features

Open an issue describing the use case and the problem it solves. Check the [ROADMAP](ROADMAP.md) first — it may already be planned.

### Submitting a Pull Request

1. Fork the repo and create a branch from `main`:
   ```bash
   git checkout -b feat/my-feature
   ```
2. Make your changes. Keep commits focused and atomic.
3. Follow the existing code style (Kotlin conventions, prefer `val` over `var`).
4. Update `CHANGELOG.md` under an `## [Unreleased]` section.
5. Push and open a PR against `main` with a clear description of:
   - What changed and why
   - How to test it
   - Screenshots/GIFs for UI changes

### Commit Messages

Conventional Commits style is appreciated but not enforced:

```
feat: add HSL sort for color alternatives
fix: prevent NPE when scope has no source files
docs: clarify install instructions
```

## Code Style

- Kotlin: follow the [official Kotlin style guide](https://kotlinlang.org/docs/coding-conventions.html)
- 4-space indentation
- Avoid wildcard imports
- Public APIs should have KDoc; internal logic doesn't need it unless non-obvious

## Scope of Contributions

Welcome:

- Bug fixes
- New token format support (JSON tokens, Tailwind config…)
- UI/UX improvements
- Performance optimizations
- Documentation & tests

Out of scope (for now):

- Major architectural rewrites without prior discussion in an issue
- Support for non-IntelliJ IDEs

## Questions?

Open a [Discussion](https://github.com/robinlopez/token-flow/discussions) or reach out via an issue.

Thanks for contributing! 🙌
