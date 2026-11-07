import { Routes } from '@angular/router';
import { ExtractionPlaceholder } from './extraction-placeholder/extraction-placeholder';
import { Upload } from './upload/upload';

export const routes: Routes = [
  { path: '', component: Upload },
  { path: 'extractions/:extractionId', component: ExtractionPlaceholder },
];
