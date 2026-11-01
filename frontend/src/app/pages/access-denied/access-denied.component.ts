import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-access-denied',
  standalone: true,
  imports: [CommonModule, RouterLink],
  template: `
    <div class="denied-card">
      <h2>Access Denied</h2>
      <p>You do not have permission to access this portal or resource.</p>
      <a routerLink="/" class="btn-return">Return to Safety</a>
    </div>
  `,
  styles: [`
    .denied-card {
      background: #ffffff;
      padding: 2.5rem;
      border-radius: 8px;
      border: 1px solid #fee2e2;
      text-align: center;
      max-width: 500px;
      margin: 3rem auto;
      h2 { color: #dc2626; margin-bottom: 0.75rem; }
      p { color: #64748b; margin-bottom: 1.5rem; }
      .btn-return {
        display: inline-block;
        padding: 0.6rem 1.2rem;
        background: #2563eb;
        color: #fff;
        border-radius: 6px;
        font-weight: 500;
      }
    }
  `]
})
export class AccessDeniedComponent {}
