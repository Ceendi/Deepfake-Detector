/**
 * Commitlint — Conventional Commits dla DeepfakeDetector.
 * Format: <type>(<scope>): <opis>   np. `feat(frontend): dodaj ekran logowania`
 * Hook commit-msg podpięty przez husky (zob. .husky/commit-msg).
 * Docs: https://www.conventionalcommits.org
 */
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // Dozwolone typy commitów (błąd, jeśli inny)
    'type-enum': [
      2,
      'always',
      [
        'feat', // nowa funkcjonalność
        'fix', // poprawka błędu
        'docs', // dokumentacja
        'style', // formatowanie, bez zmian logiki
        'refactor', // refaktor bez zmiany zachowania
        'perf', // optymalizacja wydajności
        'test', // testy
        'build', // build / zależności
        'ci', // CI/CD
        'chore', // utrzymanie, drobne zadania
        'revert', // cofnięcie zmiany
      ],
    ],
    // Scope opcjonalny; jeśli podany — sugerowane moduły repo (warning, nie blokuje)
    'scope-enum': [
      1,
      'always',
      [
        'gateway',
        'eureka-server',
        'orchestrator',
        'file-service',
        'video-detector',
        'audio-detector',
        'frontend',
        'infra',
        'docs',
        'ci',
        'deps',
        'release',
      ],
    ],
    'subject-case': [0], // pozwól na polskie zdania / dowolny case w temacie
    'header-max-length': [2, 'always', 100],
  },
}
