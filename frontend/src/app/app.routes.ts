import { Routes } from '@angular/router';
import { ExtractionPlaceholder } from './extraction-placeholder/extraction-placeholder';
import { Home } from './home';

export const routes: Routes = [
  { path: '', component: Home },
  { path: 'extractions/:extractionId', component: ExtractionPlaceholder },
];
