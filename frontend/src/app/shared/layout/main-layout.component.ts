import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-main-layout',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink],
  template: `
    <div class="layout-container">
      <header class="app-header">
        <div class="brand">
          <span class="brand-title">Invoice Factoring & Escrow Network</span>
        </div>
        <nav class="nav-links" *ngIf="authService.isAuthenticated()">
          <a *ngIf="authService.hasRole('SME')" routerLink="/sme" routerLinkActive="active">SME Portal</a>
          <a *ngIf="authService.hasRole('FINANCIER')" routerLink="/financier" routerLinkActive="active">Financier Portal</a>
        </nav>
        <div class="user-profile" *ngIf="authService.isAuthenticated(); else guestBlock">
          <span class="user-name">{{ authService.currentUser()?.fullName }}</span>
          <span class="user-role badge">{{ authService.currentUser()?.role }}</span>
          <button class="btn btn-outline" (click)="logout()">Sign Out</button>
        </div>
        <ng-template #guestBlock>
          <div class="user-profile">
            <a routerLink="/login" class="btn btn-primary">Sign In</a>
          </div>
        </ng-template>
      </header>
      <main class="main-content">
        <router-outlet></router-outlet>
      </main>
      <footer class="app-footer">
        <p>&copy; 2026 B2B Invoice Factoring & Smart Escrow Network. All rights reserved.</p>
      </footer>
    </div>
  `,
  styles: [`
    .layout-container {
      display: flex;
      flex-direction: column;
      min-height: 100vh;
    }
    .app-header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 1rem 2rem;
      background: #ffffff;
      border-bottom: 1px solid #e2e8f0;
      box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
    }
    .brand-title {
      font-weight: 700;
      font-size: 1.125rem;
      color: #0f172a;
    }
    .nav-links {
      display: flex;
      gap: 1.5rem;
      a {
        color: #475569;
        font-weight: 500;
        &.active {
          color: #2563eb;
          border-bottom: 2px solid #2563eb;
        }
      }
    }
    .user-profile {
      display: flex;
      align-items: center;
      gap: 0.75rem;
    }
    .badge {
      background: #dbeafe;
      color: #1e40af;
      padding: 0.2rem 0.5rem;
      border-radius: 4px;
      font-size: 0.75rem;
      font-weight: 600;
    }
    .btn {
      padding: 0.4rem 0.8rem;
      border-radius: 4px;
      font-size: 0.875rem;
      cursor: pointer;
      border: 1px solid transparent;
      &.btn-outline {
        border-color: #cbd5e1;
        background: transparent;
        color: #334155;
        &:hover {
          background: #f1f5f9;
        }
      }
      &.btn-primary {
        background: #2563eb;
        color: #ffffff;
        &:hover {
          background: #1d4ed8;
        }
      }
    }
    .main-content {
      flex: 1;
      padding: 2rem;
      max-width: 1200px;
      width: 100%;
      margin: 0 auto;
    }
    .app-footer {
      padding: 1rem;
      text-align: center;
      background: #ffffff;
      border-top: 1px solid #e2e8f0;
      color: #64748b;
      font-size: 0.875rem;
    }
  `]
})
export class MainLayoutComponent {
  readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  logout(): void {
    this.authService.logout();
  }
}
