import { Routes } from '@angular/router';
import { MainLayoutComponent } from './shared/layout/main-layout.component';
import { LoginComponent } from './pages/login/login.component';
import { AccessDeniedComponent } from './pages/access-denied/access-denied.component';
import { SmePortalComponent } from './features/sme/sme-portal.component';
import { FinancierPortalComponent } from './features/financier/financier-portal.component';
import { authGuard, roleGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    component: MainLayoutComponent,
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: 'login',
      },
      {
        path: 'login',
        component: LoginComponent,
      },
      {
        path: 'access-denied',
        component: AccessDeniedComponent,
      },
      {
        path: 'sme',
        component: SmePortalComponent,
        canActivate: [authGuard, roleGuard('SME')],
      },
      {
        path: 'financier',
        component: FinancierPortalComponent,
        canActivate: [authGuard, roleGuard('FINANCIER')],
      },
    ],
  },
  {
    path: '**',
    redirectTo: 'login',
  },
];
