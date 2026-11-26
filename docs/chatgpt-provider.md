# Sign in with ChatGPT in BooxDay

In **Settings → AI Settings**, choose **ChatGPT** and tap **Continue with ChatGPT**. Complete sign-in and approve plan usage in your tablet's browser. Return to BooxDay and choose a model from the list for the connected account. The browser must be on the same tablet. Leave BooxDay open until the browser returns to its loopback address.

You can connect multiple accounts or workspaces. BooxDay remembers the selected model for each registration. Use **Manage usage** to check plan eligibility and limits. If OpenAI reports a usage limit, BooxDay pauses requests for that account for the rest of the app session unless you choose **Resume ChatGPT requests**. Check usage before resuming. If sign-out cannot confirm revocation, disconnect BooxDay in ChatGPT Settings.

OpenRouter remains the default for existing installs. Changing providers requires an explicit selection in AI Settings. BooxDay keeps the OpenRouter key and model settings so you can switch back.

## Supported features and limits

- ChatGPT uses the public Responses API for text and handwriting images. BooxDay passes its existing JSON formats and schemas through to Responses. The account catalog supplies model names and slugs. Choose a model with the modalities and structured-output support you need. If OpenAI rejects a capability, choose another model.
- The subscription preview excludes AI audio and OpenRouter's per-model temperature, output-token and routing controls. Tablet speech recognition can still produce text for ChatGPT tools when available.
- Background handwriting indexing is off by default for each ChatGPT connection. With ChatGPT selected, turn on **Allow ChatGPT to index handwriting** in AI Settings. **AI Features** and **Read my handwriting** in BOOX Notes & Search must also be on. BooxDay sends memo and BOOX Notes page images to OpenAI using the selected model and account. The model needs image input and structured output support. OpenAI’s data policies apply, and your plan and BooxDay’s monthly page limits still apply. Turning the opt-in off stops new uploads and cancels in-progress reads where possible. Previously indexed pages stay searchable. OpenRouter indexing continues to require zero-data-retention routing. The legacy BOOX Notes LLM matching path remains on OpenRouter.
- Requests use `store: false`. OpenAI's data policies still apply. Calendar features show a separate first-use disclosure for each provider.
- A ChatGPT request runs once on the selected model. BooxDay returns errors for refusals, incomplete streams, unsupported input and usage limits. OpenRouter keeps its existing within-provider model retry and credit behavior. Inference waits for saved settings to load and rejects a stale provider or account selection.

## Security review and verification

- The browser flow uses an IPv4 loopback listener on an ephemeral port, an exact callback path and request host, fresh state and nonce, and S256 PKCE. The listener accepts one result, times out, and serves a static response with no referrer or cache. BooxDay stores the issued client ID before exchanging a code so a retry can reuse it.
- ID tokens require an RS256 signature from OpenAI's JWKS, the expected issuer and client audience, valid times and subject, and the sign-in nonce. Returning sign-in verifies the original subject. Opaque access tokens must come from the authenticated token endpoint with Bearer type, expiry, and granted plan permissions.
- The Android Keystore protects the AES-GCM key. BooxDay atomically replaces the encrypted profile file in Android's no-backup directory. Refresh rotation is serialized and checkpointed before verifying a refreshed identity. The handwriting opt-in is stored with that encrypted account registration on this device and cleared on sign-out. Account, model, and consent changes invalidate queued requests. Sign-out cancels active calls for the selected account. A request already sent to a provider can still be processed there.
- The HTTP client uses fixed OpenAI HTTPS origins, blocks redirects, and keeps credentials, prompts and event bodies out of transport logs. The revocation URL is fetched from discovery and constrained to OpenAI's documented endpoint. Sign-out retries transient revocation errors, clears the local tokens, and reports if revocation could not be confirmed.
- Automated tests use a real loopback socket, a local HTTP fixture and synthetic signed tokens. Live OpenAI eligibility, Android browser callback behavior on BOOX tablets, Android Keystore behavior on hardware, and actual model capabilities require device testing with an eligible account.

The Android sign-in button uses the [official ChatGPT mark](https://github.com/openai/sign-in-with-chatgpt-devkit/blob/0a36fefeb913055c8c7a1a29b63d82b96b2e841a/assets/brand/chatgpt-button-black-21.svg).

[OpenAI sign-in documentation](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) · [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions) · [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) · [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
