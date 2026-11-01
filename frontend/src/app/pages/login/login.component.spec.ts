import { ComponentFixture, TestBed } from '@angular/core/testing';
import { LoginComponent } from './login.component';
import { AuthService } from '../../core/auth/auth.service';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';

describe('LoginComponent', () => {
  let component: LoginComponent;
  let fixture: ComponentFixture<LoginComponent>;
  let authServiceSpy: jasmine.SpyObj<AuthService>;
  let routerSpy: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['login']);
    routerSpy = jasmine.createSpyObj('Router', ['navigate']);

    await TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [
        { provide: AuthService, useValue: authServiceSpy },
        { provide: Router, useValue: routerSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should initialize with invalid form', () => {
    expect(component.loginForm.valid).toBeFalse();
  });

  it('should validate email format and password length', () => {
    const emailField = component.loginForm.get('email');
    const passwordField = component.loginForm.get('password');

    emailField?.setValue('invalid-email');
    passwordField?.setValue('short');
    expect(component.loginForm.valid).toBeFalse();

    emailField?.setValue('valid@example.com');
    passwordField?.setValue('securepassword123');
    expect(component.loginForm.valid).toBeTrue();
  });

  it('should call AuthService.login and navigate to /sme on SME success', () => {
    component.loginForm.setValue({
      email: 'sme@example.com',
      password: 'password123',
    });

    authServiceSpy.login.and.returnValue(
      of({
        success: true,
        data: {
          token: 'jwt',
          userId: 'u1',
          orgId: 'o1',
          role: 'SME',
          fullName: 'SME User',
        },
      })
    );

    component.onSubmit();

    expect(authServiceSpy.login).toHaveBeenCalledWith({
      email: 'sme@example.com',
      password: 'password123',
    });
    expect(routerSpy.navigate).toHaveBeenCalledWith(['/sme']);
    expect(component.errorMessage).toBeNull();
  });

  it('should show error banner when authentication fails', () => {
    component.loginForm.setValue({
      email: 'sme@example.com',
      password: 'wrongpassword',
    });

    authServiceSpy.login.and.returnValue(
      throwError(() => new Error('Invalid credentials'))
    );

    component.onSubmit();

    expect(component.errorMessage).toBe('Invalid credentials');
    expect(routerSpy.navigate).not.toHaveBeenCalled();
  });
});
