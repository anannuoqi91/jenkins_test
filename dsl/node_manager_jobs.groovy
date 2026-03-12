// dsl/node_manager_jobs.groovy

folder('NodeManager') {
  description('Node management')
  displayName('NodeManager')
}

// =========================
// 1) AddNode
// =========================
pipelineJob('NodeManager/AddNode') {
  description('Add a Jenkins node by parameters')

  parameters {
    stringParam('NODE_NAME', '', 'Jenkins 节点名称，例如 office-node11')
    stringParam('NODE_IP', '', '节点 IP 地址')
    stringParam('SSH_PORT', '22', 'SSH 端口')
    stringParam('ROOT_USER', 'root', '远端登录用户名')
    nonStoredPasswordParam('ROOT_PASSWORD', '远端登录密码（首次接入时填写）')
    choiceParam('SYSTEM_TYPE', ['linux', 'ubuntu20', 'ubuntu22', 'centos7', 'arm64', 'x86_64'], '节点系统类型')
    stringParam('REMOTE_FS', '/home/jenkins', '远端 Jenkins 工作目录')
    stringParam('EXECUTORS', '1', '该节点 executors 数量')
    stringParam('LABELS', '', '额外节点标签，空格分隔，例如 gpu office')
    textParam('DESCRIPTION', '', '节点描述')
  }

  definition {
    cps {
      script('''
import jenkins.model.Jenkins
import hudson.model.Node
import hudson.slaves.DumbSlave
import hudson.slaves.RetentionStrategy
import hudson.plugins.sshslaves.SSHLauncher
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy

import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl

String shellQuote(String s) {
  return "'" + ((s ?: '').replace("'", "'\\"'\\"'")) + "'"
}

@NonCPS
def upsertUsernamePasswordCredential(String credId, String username, String password, String desc) {
  def store = SystemCredentialsProvider.getInstance().getStore()
  def domain = Domain.global()

  def existing = CredentialsProvider.lookupCredentials(
    com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials.class,
    Jenkins.get(),
    null,
    null
  ).find { it.id == credId }

  if (existing != null) {
    store.removeCredentials(domain, existing)
  }

  def cred = new UsernamePasswordCredentialsImpl(
    CredentialsScope.GLOBAL,
    credId,
    desc,
    username,
    password
  )
  store.addCredentials(domain, cred)
}

@NonCPS
def upsertSshNode(String nodeName,
                  String host,
                  int port,
                  String remoteFs,
                  int executors,
                  String labels,
                  String description,
                  String credId) {

  def j = Jenkins.get()
  def oldNode = j.getNode(nodeName)

  if (oldNode != null) {
    def oldComputer = j.getComputer(nodeName)
    if (oldComputer != null && oldComputer.countBusy() > 0) {
      throw new RuntimeException("节点 ${nodeName} 当前正在执行任务，不能覆盖更新")
    }
    j.removeNode(oldNode)
  }

  def launcher = new SSHLauncher(
    host,
    port,
    credId,
    null,
    null,
    null,
    null,
    60,
    3,
    15,
    new NonVerifyingKeyVerificationStrategy()
  )

  def node = new DumbSlave(
    nodeName,
    description ?: "Auto created by NodeManager/AddNode",
    remoteFs,
    String.valueOf(executors),
    Node.Mode.NORMAL,
    labels ?: '',
    launcher,
    RetentionStrategy.Always.INSTANCE,
    new LinkedList()
  )

  j.addNode(node)
  j.save()
}

@NonCPS
File metaDir() {
  def d = new File(Jenkins.get().getRootDir(), "node-manager-meta")
  if (!d.exists()) {
    d.mkdirs()
  }
  return d
}

@NonCPS
Map loadNodeMeta(String nodeName) {
  def f = new File(metaDir(), "${nodeName}.properties")
  def p = new Properties()
  if (f.exists()) {
    f.withInputStream { p.load(it) }
  }
  def m = [:]
  p.each { k, v -> m[k.toString()] = v?.toString() ?: '' }
  return m
}

@NonCPS
void saveNodeMeta(String nodeName, Map meta) {
  def f = new File(metaDir(), "${nodeName}.properties")
  def p = new Properties()
  meta.each { k, v ->
    p.setProperty(k.toString(), v == null ? '' : v.toString())
  }
  f.withOutputStream { p.store(it, "Managed by NodeManager") }
}

pipeline {
  agent { label 'built-in' }

  options {
    disableConcurrentBuilds()
    timeout(time: 30, unit: 'MINUTES')
  }

  stages {
    stage('Validate Input') {
      steps {
        script {
          if (!params.NODE_NAME?.trim()) {
            error('NODE_NAME 不能为空')
          }
          if (!(params.NODE_NAME ==~ /[A-Za-z0-9._-]+/)) {
            error('NODE_NAME 只允许字母、数字、点、下划线、短横线')
          }
          if (!params.NODE_IP?.trim()) {
            error('NODE_IP 不能为空')
          }
          if (!params.ROOT_USER?.trim()) {
            error('ROOT_USER 不能为空')
          }
          if (!params.ROOT_PASSWORD?.trim()) {
            error('ROOT_PASSWORD 不能为空')
          }
          if (!params.REMOTE_FS?.trim()) {
            error('REMOTE_FS 不能为空')
          }
          if (!(params.EXECUTORS ==~ /\\d+/)) {
            error('EXECUTORS 必须是整数')
          }
        }
      }
    }

    stage('SSH Connectivity Test') {
      steps {
        script {
          withEnv([
            "NODE_HOST=${params.NODE_IP}",
            "NODE_PORT=${params.SSH_PORT}",
            "NODE_USER=${params.ROOT_USER}",
            "NODE_PASS=${params.ROOT_PASSWORD}"
          ]) {
            sh """
              set +x
              sshpass -p "\\$NODE_PASS" ssh \\
                -o StrictHostKeyChecking=no \\
                -o UserKnownHostsFile=/dev/null \\
                -o ConnectTimeout=8 \\
                -p "\\$NODE_PORT" \\
                "\\$NODE_USER@\\$NODE_HOST" \\
                "echo SSH_OK"
            """
          }
        }
      }
    }

    stage('Probe Platform Info') {
      steps {
        script {
          withEnv([
            "NODE_HOST=${params.NODE_IP}",
            "NODE_PORT=${params.SSH_PORT}",
            "NODE_USER=${params.ROOT_USER}",
            "NODE_PASS=${params.ROOT_PASSWORD}"
          ]) {
            def probeOutput = sh(
              script: """
                set +x
                sshpass -p "\\$NODE_PASS" ssh \\
                  -o StrictHostKeyChecking=no \\
                  -o UserKnownHostsFile=/dev/null \\
                  -o ConnectTimeout=8 \\
                  -p "\\$NODE_PORT" \\
                  "\\$NODE_USER@\\$NODE_HOST" '
                    ARCH=\\$(uname -m 2>/dev/null || echo unknown)
                    if [ -f /etc/os-release ]; then
                      . /etc/os-release
                      echo "OS_ID=\\${ID:-unknown}"
                      echo "OS_VER=\\${VERSION_ID:-unknown}"
                    else
                      echo "OS_ID=unknown"
                      echo "OS_VER=unknown"
                    fi
                    echo "ARCH=\\$ARCH"
                  '
              """,
              returnStdout: true
            ).trim()

            echo probeOutput

            def kv = [:]
            probeOutput.readLines().each { line ->
              def idx = line.indexOf('=')
              if (idx > 0) {
                kv[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
              }
            }

            env.DETECTED_OS_ID  = kv['OS_ID'] ?: 'unknown'
            env.DETECTED_OS_VER = kv['OS_VER'] ?: 'unknown'
            env.DETECTED_ARCH   = kv['ARCH'] ?: 'unknown'

            def labelSet = []
            if (params.SYSTEM_TYPE?.trim()) {
              labelSet << params.SYSTEM_TYPE.trim()
            }
            if (env.DETECTED_ARCH?.trim()) {
              labelSet << env.DETECTED_ARCH.trim()
            }
            if (env.DETECTED_OS_ID?.trim()) {
              labelSet << env.DETECTED_OS_ID.trim()
            }
            if (params.LABELS?.trim()) {
              labelSet.addAll(params.LABELS.trim().split(/\\s+/) as List)
            }

            env.EFFECTIVE_LABELS = labelSet.findAll { it?.trim() }.unique().join(' ')
            echo "Detected OS_ID=${env.DETECTED_OS_ID}, OS_VER=${env.DETECTED_OS_VER}, ARCH=${env.DETECTED_ARCH}"
            echo "Effective labels: ${env.EFFECTIVE_LABELS}"
          }
        }
      }
    }

    stage('Check or Install Java') {
      steps {
        script {
          def sudoPassArg = shellQuote(params.ROOT_PASSWORD ?: '')

          withEnv([
            "NODE_HOST=${params.NODE_IP}",
            "NODE_PORT=${params.SSH_PORT}",
            "NODE_USER=${params.ROOT_USER}",
            "NODE_PASS=${params.ROOT_PASSWORD}"
          ]) {
            sh """
              set +x
              sshpass -p "\\$NODE_PASS" ssh \\
                -o StrictHostKeyChecking=no \\
                -o UserKnownHostsFile=/dev/null \\
                -o ConnectTimeout=8 \\
                -p "\\$NODE_PORT" \\
                "\\$NODE_USER@\\$NODE_HOST" "sh -s -- ${sudoPassArg}" <<'REMOTE_SCRIPT'
set -e
SUDO_PASS="\\$1"

run_privileged() {
  if command -v sudo >/dev/null 2>&1; then
    printf "%s\\\\n" "\\$SUDO_PASS" | sudo -S -- "\\$@"
  else
    "\\$@"
  fi
}

echo "[INFO] current user: \\$(whoami)"
echo "[INFO] uid: \\$(id -u)"

if command -v java >/dev/null 2>&1; then
  echo "[INFO] Java already exists"
  java -version
  exit 0
fi

echo "[INFO] Java not found, start installing..."

if command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  run_privileged apt-get update
  run_privileged apt-get install -y openjdk-17-jre-headless
elif command -v dnf >/dev/null 2>&1; then
  run_privileged dnf install -y java-17-openjdk
elif command -v yum >/dev/null 2>&1; then
  run_privileged yum install -y java-17-openjdk
else
  echo "[ERROR] Unsupported package manager, cannot install Java"
  exit 1
fi

echo "[INFO] Java installation finished"
java -version
REMOTE_SCRIPT
            """
          }
        }
      }
    }

    stage('Ensure Remote FS') {
      steps {
        script {
          def remoteFsArg = shellQuote(params.REMOTE_FS ?: '')
          def sudoPassArg = shellQuote(params.ROOT_PASSWORD ?: '')

          withEnv([
            "NODE_HOST=${params.NODE_IP}",
            "NODE_PORT=${params.SSH_PORT}",
            "NODE_USER=${params.ROOT_USER}",
            "NODE_PASS=${params.ROOT_PASSWORD}"
          ]) {
            sh """
              set +x
              sshpass -p "\\$NODE_PASS" ssh \\
                -o StrictHostKeyChecking=no \\
                -o UserKnownHostsFile=/dev/null \\
                -o ConnectTimeout=8 \\
                -p "\\$NODE_PORT" \\
                "\\$NODE_USER@\\$NODE_HOST" "sh -s -- ${remoteFsArg} ${sudoPassArg}" <<'REMOTE_SCRIPT'
set -e
NODE_REMOTE_FS="\\$1"
SUDO_PASS="\\$2"

run_privileged() {
  if command -v sudo >/dev/null 2>&1; then
    printf "%s\\\\n" "\\$SUDO_PASS" | sudo -S -- "\\$@"
  else
    "\\$@"
  fi
}

if [ -z "\\$NODE_REMOTE_FS" ]; then
  echo "[ERROR] REMOTE_FS is empty"
  exit 1
fi

run_privileged mkdir -p "\\$NODE_REMOTE_FS"
run_privileged touch "\\$NODE_REMOTE_FS/.jenkins_write_test"
run_privileged rm -f "\\$NODE_REMOTE_FS/.jenkins_write_test"

echo "[INFO] REMOTE_FS is ready: \\$NODE_REMOTE_FS"
ls -ld "\\$NODE_REMOTE_FS"
REMOTE_SCRIPT
            """
          }
        }
      }
    }

    stage('Create or Update Credential') {
      steps {
        script {
          def credId = "node-ssh-" + params.NODE_NAME.replaceAll(/[^A-Za-z0-9_.-]+/, '-')
          upsertUsernamePasswordCredential(
            credId,
            params.ROOT_USER,
            params.ROOT_PASSWORD,
            "Auto managed SSH credential for ${params.NODE_NAME}"
          )
          env.NODE_CRED_ID = credId
          echo "Credential created/updated: ${credId}"
        }
      }
    }

    stage('Create or Update Jenkins Node') {
      steps {
        script {
          def finalDescription = """
Managed by NodeManager/AddNode
IP=${params.NODE_IP}
SYSTEM_TYPE=${params.SYSTEM_TYPE}
OS_ID=${env.DETECTED_OS_ID}
OS_VER=${env.DETECTED_OS_VER}
ARCH=${env.DETECTED_ARCH}
CREDENTIAL_ID=${env.NODE_CRED_ID}
${params.DESCRIPTION ?: ''}
""".stripIndent().trim()

          upsertSshNode(
            params.NODE_NAME,
            params.NODE_IP,
            params.SSH_PORT as int,
            params.REMOTE_FS,
            params.EXECUTORS as int,
            env.EFFECTIVE_LABELS,
            finalDescription,
            env.NODE_CRED_ID
          )
        }
      }
    }

    stage('Persist Node Metadata') {
        steps {
            script {
                def existing = loadNodeMeta(params.NODE_NAME)

                saveNodeMeta(params.NODE_NAME, existing + [
                    nodeName           : params.NODE_NAME,
                    ip                 : params.NODE_IP,
                    sshPort            : params.SSH_PORT,
                    rootUser           : params.ROOT_USER,
                    systemType         : params.SYSTEM_TYPE,
                    remoteFs           : params.REMOTE_FS,
                    executors          : params.EXECUTORS,
                    labels             : env.EFFECTIVE_LABELS ?: '',
                    credentialId       : env.NODE_CRED_ID ?: '',
                    osId               : env.DETECTED_OS_ID ?: '',
                    osVer              : env.DETECTED_OS_VER ?: '',
                    arch               : env.DETECTED_ARCH ?: '',
                    reserved           : existing.get('reserved') ?: 'false',
                    reservedUntilEpoch : existing.get('reservedUntilEpoch') ?: '0',
                    reserveReason      : existing.get('reserveReason') ?: '',
                    managedBy          : 'NodeManager/AddNode'
                ])
            }
        }
    }

    stage('Verify Node Online') {
      steps {
        script {
          def computer = Jenkins.get().getComputer(params.NODE_NAME)
          if (computer == null) {
            error("Node 创建后未找到对应 computer：${params.NODE_NAME}")
          }

          echo "Start connecting node: ${params.NODE_NAME}"
          computer.connect(true)

          try {
            timeout(time: 3, unit: 'MINUTES') {
              waitUntil {
                def c = Jenkins.get().getComputer(params.NODE_NAME)
                echo "online=${c?.isOnline()}, offline=${c?.isOffline()}, tempOffline=${c?.isTemporarilyOffline()}, cause=${c?.getOfflineCauseReason()}"
                return c != null && c.isOnline()
              }
            }
          } catch (e) {
            def c = Jenkins.get().getComputer(params.NODE_NAME)
            echo "Node failed to come online."
            echo "offline=${c?.isOffline()}, tempOffline=${c?.isTemporarilyOffline()}"
            echo "offlineCause=${c?.getOfflineCauseReason()}"
            throw e
          }

          echo "Node ${params.NODE_NAME} 已成功创建并上线"
        }
      }
    }
  }
}
'''.stripIndent())
    }
  }
}


// =========================
// 4) Node_Status_Sync
// =========================
pipelineJob('NodeManager/Node_Status_Sync') {
  description('Sync reserved node status and auto release expired reservations')

  triggers {
    cron('H/2 * * * *')
  }

  definition {
    cps {
      script('''
import jenkins.model.Jenkins

@NonCPS
File metaDir() {
  def d = new File(Jenkins.get().getRootDir(), "node-manager-meta")
  if (!d.exists()) {
    d.mkdirs()
  }
  return d
}

@NonCPS
Map loadNodeMetaByFile(File f) {
  def p = new Properties()
  f.withInputStream { p.load(it) }
  def m = [:]
  p.each { k, v -> m[k.toString()] = v?.toString() ?: '' }
  return m
}

@NonCPS
void saveNodeMeta(String nodeName, Map meta) {
  def f = new File(metaDir(), "${nodeName}.properties")
  def p = new Properties()
  meta.each { k, v ->
    p.setProperty(k.toString(), v == null ? '' : v.toString())
  }
  f.withOutputStream { p.store(it, "Managed by NodeManager") }
}

pipeline {
  agent { label 'built-in' }

  stages {
    stage('Sync Reserved Nodes') {
      steps {
        script {
          def now = System.currentTimeMillis()
          def dir = metaDir()
          def files = dir.listFiles()?.findAll { it.name.endsWith('.properties') } ?: []

          files.each { f ->
            def meta = loadNodeMetaByFile(f)
            def nodeName = meta.nodeName ?: f.name.replaceFirst(/\\.properties$/, '')
            def reserved = (meta.reserved ?: 'false').toBoolean()
            def reservedUntil = (meta.reservedUntilEpoch ?: '0') as long

            if (reserved && reservedUntil > 0 && now >= reservedUntil) {
              def computer = Jenkins.get().getComputer(nodeName)
              if (computer != null) {
                computer.setTemporarilyOffline(false, null)
                echo "Auto released node: ${nodeName}"
              } else {
                echo "Node not found during auto release: ${nodeName}"
              }

              meta.reserved = 'false'
              meta.reservedUntilEpoch = '0'
              meta.reserveReason = ''
              saveNodeMeta(nodeName, meta)
            }
          }
        }
      }
    }
  }
}
'''.stripIndent())
    }
  }
}

// =========================
// 5) Node_Operation
// =========================
pipelineJob('NodeManager/Node_Operation') {
  description('Reserve / Release managed nodes')

  parameters {
    activeChoiceParam('NODE_NAME') {
      description('节点名称')
      choiceType('SINGLE_SELECT')
      filterable()
      groovyScript {
        script('''
import jenkins.model.Jenkins

def dir = new File(Jenkins.get().getRootDir(), "node-manager-meta")
if (!dir.exists()) {
  return ['<No managed nodes>']
}

def names = dir.listFiles()
  ?.findAll { it.name.endsWith('.properties') }
  ?.collect { it.name.replaceFirst(/\\.properties$/, '') }
  ?.sort()

return names ?: ['<No managed nodes>']
'''.stripIndent())
        fallbackScript('return ["<ERROR>"]')
      }
    }

    activeChoiceReactiveReferenceParam('NODE_INFO') {
      description('节点基本信息')
      choiceType('FORMATTED_HTML')
      referencedParameter('NODE_NAME')
      groovyScript {
        script('''
import jenkins.model.Jenkins

def esc = { s ->
  (s ?: '').replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace('"','&quot;')
}

if (!NODE_NAME || NODE_NAME.startsWith('<')) {
  return "<div style='padding:8px;color:#666;'>未选择有效节点</div>"
}

def file = new File(Jenkins.get().getRootDir(), "node-manager-meta/${NODE_NAME}.properties")
if (!file.exists()) {
  return "<div style='padding:8px;color:#c00;'>未找到节点元数据</div>"
}

def p = new Properties()
file.withInputStream { p.load(it) }

def ip = esc(p.getProperty('ip'))
def systemType = esc(p.getProperty('systemType'))
def labels = esc(p.getProperty('labels'))
def osId = esc(p.getProperty('osId'))
def arch = esc(p.getProperty('arch'))

return """
<div style="display:grid;grid-template-columns:120px 1fr;gap:8px;max-width:680px;padding:8px 0;">
  <label>IP</label><input type="text" value="${ip}" disabled style="background:#f5f5f5;color:#555;border:1px solid #ddd;padding:6px;" />
  <label>系统类型</label><input type="text" value="${systemType}" disabled style="background:#f5f5f5;color:#555;border:1px solid #ddd;padding:6px;" />
  <label>OS</label><input type="text" value="${osId}" disabled style="background:#f5f5f5;color:#555;border:1px solid #ddd;padding:6px;" />
  <label>架构</label><input type="text" value="${arch}" disabled style="background:#f5f5f5;color:#555;border:1px solid #ddd;padding:6px;" />
  <label>标签</label><input type="text" value="${labels}" disabled style="background:#f5f5f5;color:#555;border:1px solid #ddd;padding:6px;" />
</div>
"""
'''.stripIndent())
        fallbackScript('return "<div style=\\"color:#c00;\\">加载节点信息失败</div>"')
      }
    }

    choiceParam('ACTION', ['Reserve', 'Release'], '操作类型')
    stringParam('DURATION_MINUTES', '60', 'Reserve=锁定时长（分钟）；Release=延迟解锁分钟数（0=立即解锁）')
    textParam('REASON', '', '备注/原因')
  }

  definition {
    cps {
      script('''
import jenkins.model.Jenkins
import hudson.model.User
import hudson.slaves.OfflineCause

@NonCPS
File metaDir() {
  def d = new File(Jenkins.get().getRootDir(), "node-manager-meta")
  if (!d.exists()) {
    d.mkdirs()
  }
  return d
}

@NonCPS
Map loadNodeMeta(String nodeName) {
  def f = new File(metaDir(), "${nodeName}.properties")
  if (!f.exists()) {
    throw new RuntimeException("未找到节点元数据: ${nodeName}")
  }
  def p = new Properties()
  f.withInputStream { p.load(it) }
  def m = [:]
  p.each { k, v -> m[k.toString()] = v?.toString() ?: '' }
  return m
}

@NonCPS
void saveNodeMeta(String nodeName, Map meta) {
  def f = new File(metaDir(), "${nodeName}.properties")
  def p = new Properties()
  meta.each { k, v ->
    p.setProperty(k.toString(), v == null ? '' : v.toString())
  }
  f.withOutputStream { p.store(it, "Managed by NodeManager") }
}

pipeline {
  agent { label 'built-in' }

  options {
    disableConcurrentBuilds()
    timeout(time: 15, unit: 'MINUTES')
  }

  stages {
    stage('Validate Parameters') {
      steps {
        script {
          if (!params.NODE_NAME?.trim() || params.NODE_NAME.startsWith('<')) {
            error('请选择有效节点')
          }
          if (!(params.DURATION_MINUTES ==~ /\\d+/)) {
            error('DURATION_MINUTES 必须是非负整数')
          }
          if (!['Reserve', 'Release'].contains(params.ACTION)) {
            error('ACTION 只能是 Reserve 或 Release')
          }
        }
      }
    }

    stage('Execute Operation') {
      steps {
        script {
          def nodeName = params.NODE_NAME
          def meta = loadNodeMeta(nodeName)
          def computer = Jenkins.get().getComputer(nodeName)

          if (computer == null) {
            error("节点不存在或未注册为 Jenkins node: ${nodeName}")
          }

          long now = System.currentTimeMillis()
          long minutes = (params.DURATION_MINUTES ?: '0') as long

          if (params.ACTION == 'Reserve') {
            if (minutes <= 0) {
              error('Reserve 时 DURATION_MINUTES 必须大于 0')
            }

            long reservedUntil = now + minutes * 60_000L
            def cause = new OfflineCause.UserCause(User.current(), "Reserved by Node_Operation: ${params.REASON ?: 'no reason'}")

            computer.setTemporarilyOffline(true, cause)

            meta.reserved = 'true'
            meta.reservedUntilEpoch = String.valueOf(reservedUntil)
            meta.reserveReason = params.REASON ?: ''
            saveNodeMeta(nodeName, meta)

            echo "Node ${nodeName} reserved until ${new Date(reservedUntil)}"
          } else {
            if (minutes == 0L) {
              computer.setTemporarilyOffline(false, null)

              meta.reserved = 'false'
              meta.reservedUntilEpoch = '0'
              meta.reserveReason = ''
              saveNodeMeta(nodeName, meta)

              echo "Node ${nodeName} released immediately"
            } else {
              long releaseAt = now + minutes * 60_000L

              // 保持当前离线状态，交给 Node_Status_Sync 到点释放
              meta.reserved = 'true'
              meta.reservedUntilEpoch = String.valueOf(releaseAt)
              meta.reserveReason = "scheduled release: ${params.REASON ?: ''}"
              saveNodeMeta(nodeName, meta)

              echo "Node ${nodeName} will be released at ${new Date(releaseAt)}"
            }
          }
        }
      }
    }
  }
}
'''.stripIndent())
    }
  }
}