# Contributing to Shilling

Bug reports, documentation fixes, and code contributions are welcome. Before a large change, open an issue describing the problem and proposed approach so maintainers and contributors can coordinate.

For a code change:

1. Follow the setup instructions in [README.md](README.md).
2. Keep application data access inside the Store5/repository layer under `app/shared/.../data/store/`.
3. Use WebRTC peer-to-peer data channels for user data. The signaling server accepts only `Join`, `PeerList`, `Offer`, `Answer`, and `IceCandidate` for connection setup. Do not add another user-data transport.
4. Run `just guard-architecture` for changes to sync, server, or data flow code, and run relevant tests.
5. In the pull request, describe the behavior changed and how you verified it.

Contributions are made under this repository's [AGPL-3.0-only license](LICENSE). You must have the right to submit the code and any assets you include. Please keep third-party license notices with those assets.
