// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';
import prettier from 'eslint-config-prettier';

/**
 * Layering is enforced here, not by convention: `shared` → `core` → `layout` →
 * `features`. The patterns match on the tsconfig aliases, which is why a
 * cross-layer import has to use one — a relative `../../core/...` would slip
 * past the rule, so relative imports are for siblings only.
 *
 * `shared/` importing nothing from `core/` is the strictest of the four: what
 * it needs arrives as a DI token from `shared/shared.ports.ts`.
 */
export default tseslint.config(
  {
    ignores: ['dist/**', 'node_modules/**', '.angular/**'],
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...tseslint.configs.stylistic,
      ...angular.configs.tsRecommended,
      prettier,
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'lg', style: 'camelCase' },
      ],
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'lg', style: 'kebab-case' },
      ],
      // Zoneless: the decorator forms and the structural directives are gone and
      // must not come back.
      '@angular-eslint/prefer-signals': 'error',
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
    },
  },
  {
    files: ['src/app/shared/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@core/*', '@layout/*', '@features/*'],
              message:
                'shared/ imports nothing from the layers above it — not even types. Take a DI token from shared/shared.ports.ts.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/core/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@layout/*', '@features/*'],
              message: 'core/ sits below layout/ and features/ and must not import from them.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['src/app/layout/**/*.ts'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: ['@features/*'],
              message: 'layout/ sits below features/ and must not import from them.',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    rules: {},
  },
);
