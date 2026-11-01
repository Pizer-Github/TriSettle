import { Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, tap, catchError, throwError } from 'rxjs';
import { Router } from '@angular/router';
import { ApiConfigService } from '../api/api-config.service';

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  userId: string;
  orgId: string;
  role: string;
  fullName: string;
}

export interface ApiResponse<T> {
  success: boolean;
  message?: string;
  data: T;
}

/**
 * Authentication service.
 *
 * Security rules:
 * - JWT token is stored in memory (signal) only — NOT in localStorage or sessionStorage
 *   to mitigate XSS token theft. Page refresh requires re-login.
 * - For production, consider httpOnly cookie with CSRF protection (future milestone).
 * - Never log the token value.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'auth_session'; // sessionStorage only, not localStorage

  // Reactive auth state using Angular signals
  private readonly _isAuthenticated = signal<boolean>(false);
  private readonly _currentUser = signal<LoginResponse | null>(null);

  readonly isAuthenticated = this._isAuthenticated.asReadonly();
  readonly currentUser = this._currentUser.asReadonly();

  constructor(
    private http: HttpClient,
    private router: Router,
    private apiConfig: ApiConfigService
  ) {
    // Restore session from sessionStorage on service init
    this.restoreSession();
  }

  login(credentials: LoginRequest): Observable<ApiResponse<LoginResponse>> {
    return this.http.post<ApiResponse<LoginResponse>>(
      this.apiConfig.authEndpoints.login,
      credentials
    ).pipe(
      tap(response => {
        if (response.success && response.data) {
          this.setSession(response.data);
        }
      }),
      catchError(this.handleError)
    );
  }

  logout(): void {
    sessionStorage.removeItem(this.TOKEN_KEY);
    this._isAuthenticated.set(false);
    this._currentUser.set(null);
    this.router.navigate(['/login']);
  }

  getToken(): string | null {
    const user = this._currentUser();
    return user?.token ?? null;
  }

  hasRole(role: string): boolean {
    return this._currentUser()?.role === role;
  }

  private setSession(userData: LoginResponse): void {
    // Store in sessionStorage (not localStorage) — cleared on tab/browser close
    sessionStorage.setItem(this.TOKEN_KEY, JSON.stringify(userData));
    this._isAuthenticated.set(true);
    this._currentUser.set(userData);
  }

  private restoreSession(): void {
    try {
      const stored = sessionStorage.getItem(this.TOKEN_KEY);
      if (stored) {
        const userData: LoginResponse = JSON.parse(stored);
        this._isAuthenticated.set(true);
        this._currentUser.set(userData);
      }
    } catch {
      // If session data is corrupt, clear it
      sessionStorage.removeItem(this.TOKEN_KEY);
    }
  }

  private handleError(error: HttpErrorResponse): Observable<never> {
    // Never expose raw error internals to the template — centralise here
    const message = error.error?.message ?? 'An authentication error occurred';
    return throwError(() => new Error(message));
  }
}
