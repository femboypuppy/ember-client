# Account library

Open **Accounts** in the top-right corner of the unlocked Ember startup menu,
or use **Multiplayer → Accounts**. Back returns to the screen you came from.

The library uses Ember's Liquid Glass material, accent, and fallback appearance.
Saved accounts have player heads and account-type labels. The current account
appears first with a **Current** badge; the header shows your running session.
Search by player name or account type, then select an entry to view its details.

- **Switch account** signs in and updates the current session. Progress and
  failures appear in the screen. Repeated inputs cannot start concurrent switches.
- **Add account** opens a matching panel for Microsoft, Offline, Session, or
  Altening accounts. Microsoft opens browser sign-in asynchronously; the panel
  offers Open browser, Copy link, and Cancel sign-in. Passwords are entered in
  Microsoft's browser page. Token fields are masked.
- Successful additions are saved in the existing account format. Adding the same
  offline name or online account again updates its entry instead of duplicating it.
  Failed sign-ins do not add an entry.
- **Remove** requires a second **Confirm remove** click. Removing a saved entry
  keeps the current running session active.
- **Copy token** is available for Session and Altening accounts. Credentials
  are not rendered in the library, including unverified saved entries.

Switch accounts before entering a world. The library prevents switching a live
world's session. Network authentication runs off the client thread; collection,
save, selection and status updates return to the client thread. Browser sign-in
can be cancelled, and closed screens ignore stale completions.

Tab/Shift+Tab navigates controls, Enter activates the focused action, and Up/Down
selects saved accounts. Ctrl+F focuses search. Fields support paste, select-all
and Backspace, including Command shortcuts on macOS. Both the account list and
details scroll independently in smaller windows. Escape closes the add panel or
returns to the previous screen.

`python scripts/check_accounts.py` exercises the production screen with a
recording renderer and queued fake authentication. It covers the four account
types, failures, duplicate entries, persistence calls, cancellation, stale
callbacks, token masking, removal, world guard, GUI scales and viewport bounds.
`python scripts/check_account_login.py` runs the actual Microsoft request code
against a deterministic provider, including cancelled workers that ignore
interrupts. Both checks run in branch and release workflows. They do not sign in
to a real account or contact Microsoft. Live browser authentication still needs
confirmation with the user's account.
