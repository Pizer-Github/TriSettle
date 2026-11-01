import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * SME Portal skeleton.
 * Business dashboards and invoice listing workflows will be implemented in Milestone 2.
 */
@Component({
  selector: 'app-sme-portal',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="portal-card">
      <h2>SME Liquidity Portal</h2>
      <p class="desc">
        Welcome to your SME dashboard. Invoice submission, buyer verification, and financing listing workflows will be activated in Milestone 2.
      </p>
      <div class="status-box">
        <strong>Milestone 1 Active:</strong> Authentication and tenant context verified.
      </div>
    </div>
  `,
  styles: [`
    .portal-card {
      background: #ffffff;
      padding: 2rem;
      border-radius: 8px;
      border: 1px solid #e2e8f0;
      h2 { color: #0f172a; margin-bottom: 0.75rem; }
      .desc { color: #475569; margin-bottom: 1.5rem; }
      .status-box {
        background: #f0fdf4;
        border: 1px solid #bbf7d0;
        color: #166534;
        padding: 1rem;
        border-radius: 6px;
        font-size: 0.875rem;
      }
    }
  `]
})
export class SmePortalComponent {}
