import { ErrorHandler, Injectable } from '@angular/core';

/**
 * Centralized error handler for the Angular frontend.
 * Catches unhandled exceptions, logs them with client-side context,
 * and prevents uncaught errors from crashing the UI.
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  handleError(error: unknown): void {
    const timestamp = new Date().toISOString();
    console.error(`[GlobalErrorHandler ${timestamp}]:`, error);
  }
}
