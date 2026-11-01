import { Injectable } from '@angular/core';
import { environment } from '../../../environments/environment';

/**
 * Centralised API configuration.
 * All HTTP calls should use the baseUrl from this service —
 * never hardcode API URLs in components or services.
 */
@Injectable({ providedIn: 'root' })
export class ApiConfigService {
  readonly baseUrl = environment.apiBaseUrl;
  readonly authEndpoints = {
    login: `${this.baseUrl}/api/v1/auth/login`,
    register: `${this.baseUrl}/api/v1/auth/register`,
  };
}
