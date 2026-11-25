# WebGrab user scripts

WebGrab now behaves like a small Tampermonkey:

- Reads the `// ==UserScript==` header and runs the script **only on pages that match** `@match` / `@include` (and not `@exclude`).
- Provides `GM_xmlhttpRequest`, `GM_download`, `GM_addStyle`, `GM_setValue` / `GM_getValue` / `GM_deleteValue`, `GM_openInTab`, `GM_setClipboard`, `GM_info`, `unsafeWindow` and the `GM.*` promise versions.
- `GM_xmlhttpRequest` is made by the app itself, so there are no cross-site (CORS) limits.
- Scripts that wait for `load` or `DOMContentLoaded` still run, even though the page has already finished loading.
- Errors in a script are shown as a message instead of being hidden.
- A file that contains only the header (no code) is refused when you add it, with an explanation.

Add a script: menu -> User scripts -> Add .js file. Open a matching page (e.g. youtube.com) to test it.
Limits: `@require` and `@resource` are not loaded, menu commands are ignored, and a script with a syntax error fails silently (the page cannot report it).

## Chat Grabber (userscripts/chat-grabber.user.js)
Saves your chats **with their files** into **one ZIP** in Downloads. Inside: one folder per chat (named after the chat), each with
`chat.md` (readable), `chat.json` (full data) and a `files/` folder (attachments, images, generated files, Claude artifacts).

1. WebGrab -> User scripts -> add `chat-grabber.user.js`.
2. Open claude.ai or chatgpt.com and log in. A blue 💾 button appears at the bottom-left.
3. Tap it: **This chat** saves the open chat, **All chats** saves every chat in your account. **Stop** ends early and still saves what was collected.

Notes
- It uses the same internal web endpoints these sites use for their own pages, with your logged-in session. They are not public APIs and can change; if one stops working you get a `_notes.txt` in the ZIP, and "This chat" falls back to saving what is visible on screen.
- Archived ChatGPT chats are not listed. Files that the site no longer serves are listed in `_notes.txt`.
- The ZIP is written to Downloads piece by piece, so large exports do not fill memory (needs the updated WebGrab in this project; older builds keep it in memory, so the script then saves several smaller ZIP parts of about 25 MB each and skips single files over 30 MB).
