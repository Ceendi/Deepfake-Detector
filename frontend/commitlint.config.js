export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'feat',
        'fix',
        'docs',
        'style',
        'refactor',
        'perf',
        'test',
        'build',
        'ci', // CI/CD
        'chore',
        'revert',
      ],
    ],

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
    'subject-case': [0],
    'header-max-length': [2, 'always', 100],
  },
}
