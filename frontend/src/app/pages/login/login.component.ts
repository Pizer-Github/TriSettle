import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="login-wrapper">
      <div class="login-card">
        <h2 class="title">Sign In</h2>
        <p class="subtitle">Access the Invoice Factoring Network</p>

        <div *ngIf="errorMessage" class="error-alert" role="alert">
          {{ errorMessage }}
        </div>

        <form [formGroup]="loginForm" (ngSubmit)="onSubmit()" novalidate>
          <div class="form-group">
            <label for="email">Work Email</label>
            <input
              id="email"
              type="email"
              formControlName="email"
              placeholder="name@company.com"
              [class.invalid]="isFieldInvalid('email')"
            />
            <div *ngIf="isFieldInvalid('email')" class="field-error">
              Valid email is required
            </div>
          </div>

          <div class="form-group">
            <label for="password">Password</label>
            <input
              id="password"
              type="password"
              formControlName="password"
              placeholder="••••••••"
              [class.invalid]="isFieldInvalid('password')"
            />
            <div *ngIf="isFieldInvalid('password')" class="field-error">
              Password must be at least 8 characters
            </div>
          </div>

          <button
            type="submit"
            class="btn btn-submit"
            [disabled]="loginForm.invalid || isLoading"
          >
            <span *ngIf="!isLoading">Sign In</span>
            <span *ngIf="isLoading">Authenticating...</span>
          </button>
        </form>
      </div>
    </div>
  `,
  styles: [`
    .login-wrapper {
      display: flex;
      justify-content: center;
      align-items: center;
      padding: 3rem 1rem;
    }
    .login-card {
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 8px;
      padding: 2.5rem;
      width: 100%;
      max-width: 420px;
      box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1);
    }
    .title {
      font-size: 1.5rem;
      font-weight: 700;
      color: #0f172a;
      margin-bottom: 0.5rem;
    }
    .subtitle {
      color: #64748b;
      margin-bottom: 1.5rem;
      font-size: 0.875rem;
    }
    .error-alert {
      background: #fef2f2;
      border: 1px solid #fecaca;
      color: #b91c1c;
      padding: 0.75rem;
      border-radius: 6px;
      margin-bottom: 1rem;
      font-size: 0.875rem;
    }
    .form-group {
      margin-bottom: 1.25rem;
      label {
        display: block;
        margin-bottom: 0.375rem;
        font-weight: 500;
        font-size: 0.875rem;
        color: #334155;
      }
      input {
        width: 100%;
        padding: 0.625rem;
        border: 1px solid #cbd5e1;
        border-radius: 6px;
        font-size: 0.875rem;
        &:focus {
          outline: none;
          border-color: #2563eb;
          box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
        }
        &.invalid {
          border-color: #ef4444;
        }
      }
      .field-error {
        color: #ef4444;
        font-size: 0.75rem;
        margin-top: 0.25rem;
      }
    }
    .btn-submit {
      width: 100%;
      padding: 0.75rem;
      background: #2563eb;
      color: #ffffff;
      border: none;
      border-radius: 6px;
      font-weight: 600;
      font-size: 0.875rem;
      cursor: pointer;
      &:hover:not(:disabled) {
        background: #1d4ed8;
      }
      &:disabled {
        background: #94a3b8;
        cursor: not-allowed;
      }
    }
  `]
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly authService = inject(AuthService);
  private readonly router = inject(Router);

  isLoading = false;
  errorMessage: string | null = null;

  loginForm: FormGroup = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8)]],
  });

  isFieldInvalid(fieldName: string): boolean {
    const field = this.loginForm.get(fieldName);
    return !!field && field.invalid && (field.dirty || field.touched);
  }

  onSubmit(): void {
    if (this.loginForm.invalid) {
      this.loginForm.markAllAsTouched();
      return;
    }

    this.isLoading = true;
    this.errorMessage = null;

    this.authService.login(this.loginForm.value).subscribe({
      next: (response) => {
        this.isLoading = false;
        // Redirect based on verified role from DB
        const role = response.data?.role;
        if (role === 'SME') {
          this.router.navigate(['/sme']);
        } else if (role === 'FINANCIER') {
          this.router.navigate(['/financier']);
        } else {
          this.router.navigate(['/']);
        }
      },
      error: (err: Error) => {
        this.isLoading = false;
        this.errorMessage = err.message || 'Authentication failed. Please verify credentials.';
      }
    });
  }
}
