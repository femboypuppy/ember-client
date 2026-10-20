# AH Sell pricing and confirmation

AH Sell uses `price-each` when undercutting is disabled or no comparable auction is found. With undercutting enabled, the minimum price also applies to that fallback.

`Undercut > Match listing size` is enabled by default. Selling one Note Block compares single-item auctions, rather than dividing the price of a discounted stack of 64 and treating that as the single-item market. Disable this setting to use the previous comparison across all stack sizes. `undercut-by` applies to the complete listing; `min-price-each` applies to every item in it.

Price checks wait for every auction slot's item, quantity and total price to settle. Cached prices are keyed by item, intended listing quantity and comparison mode. A failed lookup no longer substitutes a price check up to five minutes old. Per-item, worth, balance, fee and tax lore lines are excluded from total-price parsing regardless of case. Diagnostic page samples include both stack quantity and total price.

After preparing a listing, the module closes the inventory, waits 3.5 seconds after that final close, and selects the sell slot before submitting the command. It checks that the cursor is empty, the held item/count still match, and the inventory-close packet passed client packet interception. If that packet is blocked, the module reports an inventory-close problem rather than sending a command while the server still considers the inventory open.

Closing the inventory during preparation restarts the inventory synchronization. A previously attempted move cannot skip the pickup/put-back sequence or bypass the inventory-close check.

Each dispatched `/ah sell` command is echoed locally in chat and added to command history. Chat feedback distinguishes client dispatch, a server listing confirmation, full auction slots, and a lack of confirmation. Client dispatch is not proof that a server accepted the listing. A command blocked by client packet interception stops AH Sell rather than repeatedly queuing it.

Only the server's listing confirmation increments the listed counter or records an own auction. An emptied slot alone does not prove a listing, and unsuccessful attempts no longer exclude other players' similarly priced auctions from subsequent scans. World disconnects clear pending attempts and cached prices.

`./gradlew checkAuctions` exercises the production price comparison, total/fallback/minimum calculations, lore parsing and confirmation parsing without connecting to DonutSMP.
