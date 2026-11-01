import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Route guard that requires authentication.
 * Redirects unauthenticated users to /login.
 */
export const authGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree(['/login']);
};

/**
 * Role-specific guard factory.
 * Usage: canActivate: [roleGuard('SME')]
 */
export const roleGuard = (requiredRole: string): CanActivateFn => {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (auth.isAuthenticated() && auth.hasRole(requiredRole)) {
      return true;
    }

    if (!auth.isAuthenticated()) {
      return router.createUrlTree(['/login']);
    }

    // Authenticated but wrong role
    return router.createUrlTree(['/access-denied']);
  };
};
