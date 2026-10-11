# Repository instructions

- This repository contains Azure-specific demos, not a replacement for the shared framework.
- Build with `bash build.sh`; it pins and tests the shared toolkit core before testing both modules.
- Keep all credentials, wallets, connection secrets, model outputs containing private data, and build files out of Git.
- Never present mocks or document policy fixtures as live Oracle inventory data.
- Runtime failures must not fall back to model-invented answers or web search.
- Live integration tests are opt-in and write/delete only their unique test document chunks.
- Do not change the AWS or GCP demos as part of work in this repository.
- Preserve tool allowlisting, explicit approvals, bounded SQL, and least-privilege database access.
- Track actual validation and remaining blockers concisely in README.md.
