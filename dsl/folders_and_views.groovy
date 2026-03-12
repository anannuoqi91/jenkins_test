// dsl/folders_and_views.groovy

def commonColumns = { c ->
  c.status()
  c.weather()
  c.name()
  c.lastSuccess()
  c.lastFailure()
  c.lastDuration()
  c.buildButton()
}

// =========================
// 1. 顶层文件夹
// =========================
folder('NodeManager') {
  description('Node management')
  displayName('NodeManager')
}

folder('ReleaseTest') {
  description('Release test management')
  displayName('ReleaseTest')
}

folder('IntergrationTest') {
  description('Integration test management')
  displayName('IntergrationTest')
}

folder('SoftwareRelease') {
  description('Software release management')
  displayName('SoftwareRelease')
}


// =========================
// 2. 其他 tab：也只显示对应文件夹入口
// 如果你希望点 tab 后直接看到该文件夹入口，而不是子 job，就保持这种写法
// =========================
listView('Easy_Tool') {
  description('Easy Tool folder only')
  jobs {
    regex('^SoftwareRelease$|^NodeManager$')
  }
  columns { commonColumns(delegate) }
}


listView('SoftwareTest') {
  description('SoftwareTest folder only')
  jobs {
    regex('^IntergrationTest$|^SoftwareTest$')
  }
  columns { commonColumns(delegate) }
}