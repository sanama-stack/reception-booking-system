import { dirname } from 'path';
import { fileURLToPath } from 'url';
import { FlatCompat } from '@eslint/eslintrc';

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

const config = [
  { ignores: ['.next/**', 'node_modules/**', 'next-env.d.ts'] },
  ...compat.extends('next/core-web-vitals', 'next/typescript', 'prettier'),
  {
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'error',
      // All datetimes render in the business timezone, through lib/time (ADR-0003).
      // Using the browser's locale formatter anywhere in this application is a defect.
      'no-restricted-properties': [
        'error',
        {
          object: 'Intl',
          property: 'DateTimeFormat',
          message: 'Use lib/time helpers — they require an explicit business timezone (ADR-0003).',
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          selector:
            'CallExpression[callee.property.name=/^(toLocaleDateString|toLocaleTimeString|toLocaleString)$/]',
          message: 'Use lib/time helpers — they require an explicit business timezone (ADR-0003).',
        },
      ],
    },
  },
];

export default config;
