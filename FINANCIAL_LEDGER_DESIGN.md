# FINANCIAL_LEDGER_DESIGN.md

## 1. Accounting Perspectives & Entity Separation
*   **Ledger Segmentation (PENDING BUSINESS APPROVAL):** The double-entry ledger must explicitly define whose balance sheet is being updated. The platform must logically separate:
    *   **Platform Books:** Platform fee revenue, operating cash, and platform-owned liabilities.
    *   **Financier Books:** Financier cash, financier loan receivables, and purchased invoice assets.
    *   **FBO/Escrow Funds:** Cash held at external providers strictly "For Benefit Of" clients.
*   *(Note: No accounting policy for consolidating or segregating these balance sheets is finalized. The database must track the exact `accounting_entity` for every account).*

## 2. Loan Accounting Lifecycle (Product B)
*   **Funding:** Debit `ASSET_LOAN_PRINCIPAL`, Credit `LIABILITY_FINANCIER_CASH`.
*   **Interest Accrual:** Debit `ASSET_ACCRUED_INTEREST`, Credit `REVENUE_INTEREST`.
*   **Repayment Receipt:** Debit `ASSET_COLLECTION_CASH`, Credit `LIABILITY_UNALLOCATED_PAYMENT`.
*   **Allocation:** Debit `LIABILITY_UNALLOCATED_PAYMENT`. Credit `ASSET_ACCRUED_INTEREST`, Credit `ASSET_LOAN_PRINCIPAL`, Credit `REVENUE_PLATFORM_FEE`.
*   **Reversal:** Create a strictly linked `REVERSAL` journal swapping Debits and Credits of the original entry.

## 3. Invoice-Sale Settlement (Product A)
*   **Initial Recognition:** Financier funds. Reserve is held in `LIABILITY_SME_RESERVE`.
*   **Non-Recourse Default Allocation:** Buyer defaults on ₹100,000 invoice (pure credit default). Financier takes the loss. `LIABILITY_SME_RESERVE` of ₹10,000 is still owed and paid out to the SME from platform/financier operating accounts (per contract).

## 4. Recourse & Reversal Accounting Separation
*   **Error Reversals:** If a mathematical or data-entry error occurs, the original `journal_entry` is counter-balanced by a `REVERSAL` journal. The original journal is never altered.
*   **Recourse Demands (Not Reversals):** If a non-recourse Invoice Sale becomes subject to a recourse demand (e.g., due to SME fraud), **do not reverse the original sale journal**. 
*   **Recourse Accounting:** A recourse demand generates a *new* financial workflow. It creates a new `Recourse Receivable` (SME owes Financier) and tracks the subsequent `Recourse Payment Receipt`. The original invoice sale remains a permanent historical fact.