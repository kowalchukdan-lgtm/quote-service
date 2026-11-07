import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideApi } from './api';
import { routes } from './app.routes';

// The contract's server URL is a placeholder. An empty base path keeps requests relative
// (/v1/...), so `ng serve` proxies them and production serves them from the same origin.
export const API_BASE_PATH = '';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(),
    provideApi(API_BASE_PATH),
    provideRouter(routes, withComponentInputBinding())
  ]
};
