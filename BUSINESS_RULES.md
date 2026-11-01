# BUSINESS_RULES.md

## 1. Invoice Verification (Risk-Based)
*   **Baseline:** Duplicate checks (Hash + SME + Buyer + Invoice Num + Amount), eligibility checks.
*   **Buyer Confirmation:** Risk-based (first-time buyer, large anomaly). Not mandatory for low-risk established relationships.

## 2. Product A: Invoice Sale (Non-Recourse Factoring)
*   **Legal Concept:** Financier purchases the asset. Buyer pays the Platform Collection Account/FBO account (depending on final regulatory structure).
*   **Invoice-Sale Reserve Arrangements (PENDING BUSINESS APPROVAL):** The platform must support multiple reserve configurations depending on final accounting and legal decisions. The system must not assume one default model. Alternatives include:
    *   *Option A (Deferred Payment):* Financier retains the reserve cash. Platform records a deferred payment obligation to the SME, payable upon buyer settlement.
    *   *Option B (Escrowed Reserve):* Financier funds 100% of purchase price into an FBO/Escrow account. Platform segregates the advance for the SME and the reserve.
    *   *Ordinary Buyer Default:* In a non-recourse sale, ordinary credit default means the Financier bears the loss. The system must explicitly configure whether the SME reserve is completely protected, proportionally reduced, or fully forfeit under these specific contractual terms.
*   **Recourse Triggers:** SME buyback is ONLY demanded for Commercial Disputes, Fraud, or breached representations, never for ordinary credit default.

## 3. Product B: Invoice-Backed Loan (Recourse)
*   **Legal Concept:** Loan secured by the invoice. SME retains credit risk.
*   **Payment Flow:** Buyer pays SME. SME repays Financier based on a schedule.
*   **Recourse:** Standard default rules apply if SME misses repayment.

## 4. Active Financing Restriction & Encumbrance
*   **Invariant:** An invoice can only be associated with one active financing obligation at a time.
*   **Defaulted Contracts:** An invoice associated with a `DEFAULTED` or `DISPUTED` contract remains encumbered. A default does NOT release the invoice for new financing.
*   **Termination:** An invoice is only released for new financing if the previous listing/contract transitions explicitly to `CANCELLED`, or explicitly to `ENCUMBRANCE_TERMINATED` (e.g., an admin-approved release following a settled dispute).