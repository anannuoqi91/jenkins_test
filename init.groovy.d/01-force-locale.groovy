import jenkins.model.Jenkins

def tryInvoke(obj, String methodName, Object arg) {
  try {
    def m = obj.metaClass.getMetaMethod(methodName, arg)
    if (m != null) { obj."$methodName"(arg); return true }
  } catch (Throwable ignored) {}
  return false
}

try {
  // Locale plugin 的实现类常见是 hudson.plugins.locale.PluginImpl
  def clazz = Jenkins.instance.pluginManager.uberClassLoader.loadClass('hudson.plugins.locale.PluginImpl')
  def plugin = clazz.getMethod('get').invoke(null)

  boolean ok1 = tryInvoke(plugin, 'setSystemLocale', 'zh_CN') ||
                tryInvoke(plugin, 'setSystemLocale', 'zh_CN.UTF-8')

  boolean ok2 = tryInvoke(plugin, 'setIgnoreAcceptLanguage', true) ||
                tryInvoke(plugin, 'setIgnoreBrowserPreference', true)

  plugin.save()
  println("[init] Force locale => zh_CN, ignore browser language. ok1=${ok1}, ok2=${ok2}")
} catch (Throwable t) {
  println("[init] Failed to configure locale plugin: " + t)
}
