# Multi-stage processing demo

Buys 100 t of raw granite and runs it through three steps, selling at each stage. API only, so stock, GL and lots are real.

1. Back up the database first.
2. `python run.py --confirm MENA` with the stack running. Each run creates new products, tagged by date and time.

| Step | In | Out | Added cost |
|---|---|---|---|
| Primary cutting | 60 t raw | 48 t blocks, 12 t loss | $500 |
| Polishing | 20 t blocks | 18 t polished, 2 t loss | $400 |
| Tile cutting | 10 t polished | 9 t tiles, 1 t loss | $450 |

It then invoices 8 t of blocks, 3 t of polished and 4 t of tiles. Open **Manufacturing → Processing Dashboard**, **Stock by Stage** and a lot's **Trace**.

The invoice issue uses the organisation's first warehouse, so the seeder works in that location.
