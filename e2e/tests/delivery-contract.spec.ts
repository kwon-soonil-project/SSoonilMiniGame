import { execFileSync, spawnSync } from 'node:child_process'
import { chmodSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { expect, test } from '@playwright/test'
import { parse } from 'yaml'

const repositoryRoot = path.resolve(import.meta.dirname, '../..')

function bashPath(filePath: string) {
  if (process.platform !== 'win32') return filePath
  return filePath.replace(/^([A-Za-z]):/, (_, drive: string) => `/${drive.toLowerCase()}`).replaceAll('\\', '/')
}

function bashExecutable() {
  return process.platform === 'win32' ? 'C:\\Program Files\\Git\\bin\\bash.exe' : 'bash'
}

function deploymentEnvironment() {
  return {
    APP_ALLOWED_ORIGINS: 'https://preview.example.run.app,https://production.example.run.app',
    APP_IMAGE: 'minigame:verified-sha',
    APP_SESSION_SECRET_NAME: 'minigame-session-secret',
    CLOUD_RUN_RUNTIME_SERVICE_ACCOUNT: 'minigame-runtime@ssoonil-minigame-20260823.iam.gserviceaccount.com',
    CLOUD_RUN_SERVICE: 'minigame',
    CLOUD_SQL_INSTANCE: 'ssoonil-minigame-20260823:asia-northeast3:minigame-db',
    DB_NAME: 'minigame',
    DB_PASSWORD_SECRET_NAME: 'minigame-db-password',
    DB_USER: 'minigame_app',
    GCP_ARTIFACT_IMAGE: 'minigame',
    GCP_ARTIFACT_REPOSITORY: 'minigame',
    GCP_PROJECT_ID: 'ssoonil-minigame-20260823',
    GCP_REGION: 'asia-northeast3',
    GITHUB_SHA: '0123456789abcdef',
    IP_HASH_SECRET_NAME: 'minigame-ip-hash-secret',
  }
}

test('compose runs the requested verified image while retaining a local build contract', () => {
  const output = execFileSync('docker', ['compose', 'config', '--format', 'json'], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: { ...process.env, APP_IMAGE: 'minigame:verified-test-sha' },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const compose = JSON.parse(output) as {
    services: { app: { image?: string; build?: { context?: string } } }
  }

  expect(compose.services.app.image).toBe('minigame:verified-test-sha')
  expect(compose.services.app.build?.context).toBe(repositoryRoot)
})

test('Linux build entry points keep Unix line endings after checkout', () => {
  const linuxEntryPoints = [
    'backend/gradlew',
    '.github/scripts/deploy-cloud-run.sh',
    '.github/scripts/verify-delivery-candidate.sh',
  ]

  for (const entryPoint of linuxEntryPoints) {
    expect(readFileSync(path.join(repositoryRoot, entryPoint), 'utf8'), entryPoint).not.toContain('\r')
  }
})

test('Cloud Run deployment publishes the verified image with the request-based single-instance contract', () => {
  const fixtureDirectory = mkdtempSync(path.join(tmpdir(), 'minigame-deploy-contract-'))
  const commandLog = path.join(fixtureDirectory, 'commands.log')
  const githubOutput = path.join(fixtureDirectory, 'github-output.txt')
  const recorder = (command: string) => `#!/usr/bin/env bash\nset -euo pipefail\nprintf '%s' '${command}' >> \"\${COMMAND_LOG}\"\nprintf '\\t%s' \"\$@\" >> \"\${COMMAND_LOG}\"\nprintf '\\n' >> \"\${COMMAND_LOG}\"\n`
  const dockerRecorder = path.join(fixtureDirectory, 'docker')
  const gcloudRecorder = path.join(fixtureDirectory, 'gcloud')
  writeFileSync(
    dockerRecorder,
    `${recorder('docker')}if [[ \"\${1:-}\" == \"push\" ]]; then printf '%s\\n' 'pushed: digest: sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa size: 1234'; fi\n`,
  )
  writeFileSync(gcloudRecorder, recorder('gcloud'))
  chmodSync(dockerRecorder, 0o755)
  chmodSync(gcloudRecorder, 0o755)

  execFileSync(bashExecutable(), [bashPath(path.join(repositoryRoot, '.github/scripts/deploy-cloud-run.sh'))], {
    cwd: repositoryRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      COMMAND_LOG: bashPath(commandLog),
      EXPECTED_IMAGE_DIGEST: 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
      GITHUB_OUTPUT: bashPath(githubOutput),
      DOCKER_BIN: bashPath(dockerRecorder),
      GCLOUD_BIN: bashPath(gcloudRecorder),
      ...deploymentEnvironment(),
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })

  const commands = readFileSync(commandLog, 'utf8')
  const targetImage = 'asia-northeast3-docker.pkg.dev/ssoonil-minigame-20260823/minigame/minigame:0123456789abcdef'
  const immutableImage = 'asia-northeast3-docker.pkg.dev/ssoonil-minigame-20260823/minigame/minigame@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  expect(commands).toContain(`docker\ttag\tminigame:verified-sha\t${targetImage}`)
  expect(commands).toContain(`docker\tpush\t${targetImage}`)
  expect(commands).toContain(`gcloud\trun\tdeploy\tminigame`)
  expect(commands).toContain(`--project=ssoonil-minigame-20260823`)
  expect(commands).toContain(`--region=asia-northeast3`)
  expect(commands).toContain(`--image=${immutableImage}`)
  expect(commands).toContain('--min=0')
  expect(commands).toContain('--max=1')
  expect(commands).toContain('--cpu=1')
  expect(commands).toContain('--memory=512Mi')
  expect(commands).toContain('--concurrency=80')
  expect(commands).toContain('--timeout=3600')
  expect(commands).toContain('--cpu-throttling')
  expect(commands).toContain('--service-account=minigame-runtime@ssoonil-minigame-20260823.iam.gserviceaccount.com')
  expect(commands).toContain(
    '--update-env-vars=^@^DB_URL=jdbc:postgresql:///minigame?cloudSqlInstance=ssoonil-minigame-20260823:asia-northeast3:minigame-db&socketFactory=com.google.cloud.sql.postgres.SocketFactory&cloudSqlRefreshStrategy=lazy@DB_USER=minigame_app@APP_SESSION_SECURE=true@APP_ALLOWED_ORIGINS=https://preview.example.run.app,https://production.example.run.app',
  )
  expect(commands).toContain(
    '--update-secrets=DB_PASSWORD=minigame-db-password:latest,APP_SESSION_SECRET=minigame-session-secret:latest,APP_ABUSE_IP_HASH_SECRET=minigame-ip-hash-secret:latest',
  )
  expect(readFileSync(githubOutput, 'utf8')).toContain(
    'image_digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
  )
})

test('production promotion stops when its pushed digest differs from preview', () => {
  const fixtureDirectory = mkdtempSync(path.join(tmpdir(), 'minigame-digest-contract-'))
  const commandLog = path.join(fixtureDirectory, 'commands.log')
  const recorder = (command: string) => `#!/usr/bin/env bash\nset -euo pipefail\nprintf '%s' '${command}' >> \"\${COMMAND_LOG}\"\nprintf '\\t%s' \"\$@\" >> \"\${COMMAND_LOG}\"\nprintf '\\n' >> \"\${COMMAND_LOG}\"\n`
  const dockerRecorder = path.join(fixtureDirectory, 'docker')
  const gcloudRecorder = path.join(fixtureDirectory, 'gcloud')
  writeFileSync(
    dockerRecorder,
    `${recorder('docker')}if [[ \"\${1:-}\" == \"push\" ]]; then printf '%s\\n' 'pushed: digest: sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb size: 1234'; fi\n`,
  )
  writeFileSync(gcloudRecorder, recorder('gcloud'))
  chmodSync(dockerRecorder, 0o755)
  chmodSync(gcloudRecorder, 0o755)

  const result = spawnSync(
    bashExecutable(),
    [bashPath(path.join(repositoryRoot, '.github/scripts/deploy-cloud-run.sh'))],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      env: {
        ...process.env,
        COMMAND_LOG: bashPath(commandLog),
        DOCKER_BIN: bashPath(dockerRecorder),
        EXPECTED_IMAGE_DIGEST: 'sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
        GCLOUD_BIN: bashPath(gcloudRecorder),
        ...deploymentEnvironment(),
      },
    },
  )

  expect(result.status).toBe(4)
  expect(result.stderr).toContain('does not match preview digest')
  expect(readFileSync(commandLog, 'utf8')).not.toContain('gcloud\trun\tdeploy')
})

test('Cloud Run deployment stops before side effects when a required setting is missing', () => {
  const result = spawnSync(
    bashExecutable(),
    [bashPath(path.join(repositoryRoot, '.github/scripts/deploy-cloud-run.sh'))],
    {
      cwd: repositoryRoot,
      encoding: 'utf8',
      env: {
        ...process.env,
        ...deploymentEnvironment(),
        GCP_PROJECT_ID: '',
      },
    },
  )

  expect(result.status).not.toBe(0)
  expect(result.stderr).toContain('GCP_PROJECT_ID')
})

test('main delivery deploys preview automatically and gates production behind a manual workflow run', () => {
  const workflow = parse(
    readFileSync(path.join(repositoryRoot, '.github/workflows/ci.yml'), 'utf8'),
  ) as {
    on?: Record<string, unknown>
    jobs: Record<string, {
      concurrency?: { group?: string; 'cancel-in-progress'?: boolean }
      environment?: string
      env?: Record<string, string>
      if?: string
      needs?: string
      permissions?: Record<string, string>
      steps: Array<{ uses?: string; run?: string; with?: Record<string, string> }>
    }>
  }
  const preview = workflow.jobs['preview-contract']
  const production = workflow.jobs['production-contract']

  expect(workflow.on).toHaveProperty('workflow_dispatch')

  for (const job of [preview, production]) {
    expect(job.permissions).toEqual({ contents: 'read', 'id-token': 'write' })
    expect(job.steps.some(step => step.uses === 'google-github-actions/auth@v3')).toBe(true)
    expect(job.steps.some(step => step.uses === 'google-github-actions/setup-gcloud@v3')).toBe(true)
    expect(job.steps.some(step => step.run === 'bash .github/scripts/deploy-cloud-run.sh')).toBe(true)
    const auth = job.steps.find(step => step.uses === 'google-github-actions/auth@v3')
    expect(auth?.with).toEqual({
      project_id: '${{ vars.GCP_PROJECT_ID }}',
      workload_identity_provider: '${{ vars.GCP_WORKLOAD_IDENTITY_PROVIDER }}',
      service_account: '${{ vars.GCP_DEPLOY_SERVICE_ACCOUNT }}',
    })
    expect(job.env?.APP_IMAGE).toBe('minigame:${{ github.sha }}')
  }

  expect(preview.environment).toBe('preview')
  expect(preview.if).toContain("github.event_name == 'push'")
  expect(preview.if).toContain("github.event_name == 'workflow_dispatch'")
  expect(preview.if).toContain("github.ref == 'refs/heads/main'")
  expect(preview.concurrency).toEqual({ group: 'cloud-run-preview', 'cancel-in-progress': true })
  expect(preview.env?.APP_ALLOWED_ORIGINS).toBe(
    'https://minigame-preview-22353579802.asia-northeast3.run.app',
  )
  expect(preview.env?.CLOUD_RUN_SERVICE).toBe('minigame-preview')
  expect(preview.env?.DB_NAME).toBe('minigame_preview')
  expect(preview.env?.DB_USER).toBe('minigame_preview_app')
  expect(preview.env?.DB_PASSWORD_SECRET_NAME).toBe('minigame-preview-db-password')
  expect(production.environment).toBe('production')
  expect(production.if).toContain("github.event_name == 'workflow_dispatch'")
  expect(production.if).toContain("github.ref == 'refs/heads/main'")
  expect(production.concurrency).toEqual({ group: 'cloud-run-production', 'cancel-in-progress': false })
  expect(production.env?.APP_ALLOWED_ORIGINS).toBe(
    'https://minigame-22353579802.asia-northeast3.run.app',
  )
  expect(production.env?.CLOUD_RUN_SERVICE).toBe('minigame')
  expect(production.env?.DB_NAME).toBe('${{ vars.DB_NAME }}')
  expect(production.env?.DB_USER).toBe('${{ vars.DB_USER }}')
  expect(production.env?.DB_PASSWORD_SECRET_NAME).toBe('${{ vars.DB_PASSWORD_SECRET_NAME }}')
  expect(production.env?.EXPECTED_IMAGE_DIGEST).toBe('${{ needs.preview-contract.outputs.image_digest }}')
  expect(production.needs).toBe('preview-contract')
})
