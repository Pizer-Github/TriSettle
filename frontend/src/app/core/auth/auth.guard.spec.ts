import { TestBed } from '@angular/core/testing';
import { Router, UrlTree } from '@angular/router';
import { RouterTestingModule } from '@angular/router/testing';
import { HttpClientTestingModule } from '@angular/common/http/testing';
import { authGuard, roleGuard } from './auth.guard';
import { AuthService } from './auth.service';

describe('AuthGuards', () => {
  let authService: AuthService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RouterTestingModule, HttpClientTestingModule],
      providers: [AuthService],
    });
    authService = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  it('authGuard should redirect unauthenticated users to /login', () => {
    spyOn(authService, 'isAuthenticated').and.returnValue(false);
    const result = TestBed.runInInjectionContext(() => authGuard({} as any, {} as any));

    expect(result instanceof UrlTree).toBeTrue();
    expect((result as UrlTree).toString()).toBe('/login');
  });

  it('authGuard should allow authenticated users', () => {
    spyOn(authService, 'isAuthenticated').and.returnValue(true);
    const result = TestBed.runInInjectionContext(() => authGuard({} as any, {} as any));

    expect(result).toBeTrue();
  });

  it('roleGuard should allow user with matching role', () => {
    spyOn(authService, 'isAuthenticated').and.returnValue(true);
    spyOn(authService, 'hasRole').withArgs('SME').and.returnValue(true);

    const guard = roleGuard('SME');
    const result = TestBed.runInInjectionContext(() => guard({} as any, {} as any));

    expect(result).toBeTrue();
  });

  it('roleGuard should redirect to /access-denied if user has different role', () => {
    spyOn(authService, 'isAuthenticated').and.returnValue(true);
    spyOn(authService, 'hasRole').withArgs('FINANCIER').and.returnValue(false);

    const guard = roleGuard('FINANCIER');
    const result = TestBed.runInInjectionContext(() => guard({} as any, {} as any));

    expect(result instanceof UrlTree).toBeTrue();
    expect((result as UrlTree).toString()).toBe('/access-denied');
  });
});
