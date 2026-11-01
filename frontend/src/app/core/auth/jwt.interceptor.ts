import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * JWT HTTP interceptor.
 * Attaches the Authorization header to every outgoing API request.
 *
 * Security rules:
 * - Only attaches the token to requests targeting the configured API base URL.
 * - NEVER logs the token value.
 * - On 401, clears the session and redirects to login.
 */
export const jwtInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const token = auth.getToken();

  let authReq = req;
  if (token) {
    authReq = req.clone({
      setHeaders: {
        Authorization: `Bearer ${token}`,
      },
    });
  }

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401) {
        // Token expired or invalid — clear session and redirect to login
        auth.logout();
        router.navigate(['/login']);
      }
      return throwError(() => error);
    })
  );
};
