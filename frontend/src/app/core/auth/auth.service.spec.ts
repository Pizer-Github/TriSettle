import { TestBed } from '@angular/core/testing';
import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { AuthService, LoginRequest, LoginResponse, ApiResponse } from './auth.service';
import { ApiConfigService } from '../api/api-config.service';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule, RouterTestingModule],
      providers: [AuthService, ApiConfigService],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.clear();
  });

  it('should be initialized with unauthenticated state', () => {
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.currentUser()).toBeNull();
    expect(service.getToken()).toBeNull();
  });

  it('should authenticate user and store session upon successful login', () => {
    const mockCredentials: LoginRequest = {
      email: 'sme@test.com',
      password: 'Password123!',
    };

    const mockResponse: ApiResponse<LoginResponse> = {
      success: true,
      data: {
        token: 'mock-jwt-token-xyz',
        userId: '11111111-1111-1111-1111-111111111111',
        orgId: '22222222-2222-2222-2222-222222222222',
        role: 'SME',
        fullName: 'Test SME User',
      },
    };

    service.login(mockCredentials).subscribe((res) => {
      expect(res.success).toBeTrue();
      expect(service.isAuthenticated()).toBeTrue();
      expect(service.getToken()).toBe('mock-jwt-token-xyz');
      expect(service.hasRole('SME')).toBeTrue();
      expect(service.hasRole('FINANCIER')).toBeFalse();
    });

    const req = httpMock.expectOne('http://localhost:8080/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    req.flush(mockResponse);
  });

  it('should clear session on logout', () => {
    const mockUser: LoginResponse = {
      token: 'dummy-token',
      userId: 'u1',
      orgId: 'o1',
      role: 'SME',
      fullName: 'User One',
    };
    (service as any).setSession(mockUser);
    expect(service.isAuthenticated()).toBeTrue();

    service.logout();
    expect(service.isAuthenticated()).toBeFalse();
    expect(service.currentUser()).toBeNull();
    expect(service.getToken()).toBeNull();
  });
});
