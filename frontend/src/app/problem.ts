import { HttpErrorResponse } from '@angular/common/http';
import { Problem, ValidationProblem } from './api';

export function isProblem(body: unknown): body is Problem {
  return typeof body === 'object' && body !== null && typeof (body as Problem).title === 'string';
}

/** The Problem Details body of an HTTP error, if it has one. */
export function problemOf(error: unknown): ValidationProblem | undefined {
  const body: unknown = error instanceof HttpErrorResponse ? error.error : undefined;
  return isProblem(body) ? body : undefined;
}
