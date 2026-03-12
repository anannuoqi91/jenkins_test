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

pipeline {
  agent any

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
// 2) Reserve_Node
// =========================
pipelineJob('NodeManager/Reserve_Node') {
  description('Reserve node placeholder')

  parameters {
    stringParam('NODE_NAME', '', '要锁定的节点名称')
    textParam('REASON', '', '锁定原因')
  }

  definition {
    cps {
      script('''
pipeline {
  agent any
  stages {
    stage('Reserve Node') {
      steps {
        echo "Reserve node: ${params.NODE_NAME}"
        echo "Reason: ${params.REASON}"
        echo "TODO: 在这里补充节点锁定逻辑"
      }
    }
  }
}
'''.stripIndent())
      sandbox()
    }
  }
}

// =========================
// 3) Release_Node
// =========================
pipelineJob('NodeManager/Release_Node') {
  description('Release node placeholder')

  parameters {
    stringParam('NODE_NAME', '', '要释放的节点名称')
  }

  definition {
    cps {
      script('''
pipeline {
  agent any
  stages {
    stage('Release Node') {
      steps {
        echo "Release node: ${params.NODE_NAME}"
        echo "TODO: 在这里补充节点释放逻辑"
      }
    }
  }
}
'''.stripIndent())
      sandbox()
    }
  }
}

// =========================
// 4) Node_Status_Sync
// =========================
pipelineJob('NodeManager/Node_Status_Sync') {
  description('Node status sync placeholder')

  definition {
    cps {
      script('''
pipeline {
  agent any
  stages {
    stage('Sync Status') {
      steps {
        echo "TODO: 在这里补充节点状态同步逻辑"
      }
    }
  }
}
'''.stripIndent())
      sandbox()
    }
  }
}

// =========================
// 5) Node_Operation
// =========================
pipelineJob('NodeManager/Node_Operation') {
  description('Node operation placeholder')

  parameters {
    stringParam('NODE_NAME', '', '节点名称')
    choiceParam('ACTION', ['noop', 'reconnect', 'disconnect'], '操作类型')
  }

  definition {
    cps {
      script('''
pipeline {
  agent any
  stages {
    stage('Node Operation') {
      steps {
        echo "Node: ${params.NODE_NAME}"
        echo "Action: ${params.ACTION}"
        echo "TODO: 在这里补充节点操作逻辑"
      }
    }
  }
}
'''.stripIndent())
      sandbox()
    }
  }
}