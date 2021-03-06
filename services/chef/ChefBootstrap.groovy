/*******************************************************************************
* Copyright (c) 2011 GigaSpaces Technologies Ltd. All rights reserved
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*       http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*******************************************************************************/

import org.hyperic.sigar.OperatingSystem
import org.cloudifysource.dsl.context.ServiceContextFactory
import org.cloudifysource.dsl.context.ServiceContext
import groovy.json.JsonOutput
import static Shell.*

class ChefBootstrap {
    Map chefConfig
    def osConfig
    def os
    def chefBinPath
    ServiceContext context = null
    def opscode_gpg_key_url = "http://apt.opscode.com/packages@opscode.com.gpg.key"

    def static getBootstrap(options=[:]) {
        def os = OperatingSystem.getInstance()
        def cls
        switch (os.getVendor()) {
            case ["Ubuntu", "Debian", "Mint"]: cls = new DebianBootstrap(options); break
            case ["Red Hat", "CentOS", "Fedora", "Amazon"]: cls = new RHELBootstrap(options); break
            case "SuSE":  cls = new SuSEBootstrap(options); break
            case "Win32": cls = new WindowsBootstrap(options); break
            default: throw new Exception("Support for the OS #{os.getVendor()} is not implemented")
        }
        return cls
    }

    def ChefBootstrap(options=[:]) {
        os = OperatingSystem.getInstance()
        if ("context" in options) {
            context = options["context"]
        }
        if (context.is(null)) {
            context = ServiceContextFactory.getServiceContext()
        }
        def chefProperties = new ConfigSlurper().parse(new File(pathJoin(context.getServiceDirectory(), "chef.properties")).toURL())
        osConfig = os.isWin32() ? chefProperties.win32 : chefProperties.unix

        // Load chef config from context attributes
        chefConfig = context.attributes.thisInstance.containsKey("chefConfig") ? context.attributes.thisInstance.get("chefConfig") : [:]
        // merge configs: defaults from properties file, persisted config from attributes, options given to this function
        chefConfig = chefProperties.chef.flatten() + chefConfig + options.findAll(){ it.key != "context" }
        // persist to context attributes
        context.attributes.thisInstance["chefConfig"] = chefConfig
    }
    def install() {
        if (which("chef-solo").isEmpty()) {
            switch(chefConfig.installFlavor) {
                case ["fatBinary", "pkg"]: break
                default:
                    if (which("ruby").isEmpty()) {
                        if (this.class.methods.find { it.name == "install_pkgs"}) {
                            install_pkgs(rubyPkgs)
                        } else {
                            rvm()
                        }
                    }
            }
            this.invokeMethod("${chefConfig.installFlavor}Install", null)
        }
    }
    def gemInstall() {
        def opts = "-y --no-rdoc --no-ri"
        if (!chefConfig.version.is(null)) {
            opts = "-v ${chefConfig.version} " + opts
        }
        sudo("gem install chef ${opts}")
    }
    def mkChefDirs() {
        sudo("mkdir -p '/etc/chef' '/var/chef' '/var/log/chef'")
    }
    def configureClient() {
        mkChefDirs()
        sudoWriteFile("/etc/chef/client.rb", """
log_level          :info
log_location       "/var/log/chef/client.log"
ssl_verify_mode    :verify_none
validation_client_name "chef-validator"
validation_key         "/etc/chef/validation.pem"
client_key               "/etc/chef/client.pem"
chef_server_url    "${chefConfig.serverURL}"
file_cache_path    "/var/chef/cache"
file_backup_path  "/var/chef/backup"
pid_file           "/var/run/chef/client.pid"
Chef::Log::Formatter.show_time = true
""")
        if (chefConfig.validationCert) {
            sudoWriteFile("/etc/chef/validation.pem", chefConfig.validationCert)
        } else {
            sudo("cp ${System.properties["user.home"]}/gs-files/validation.pem /etc/chef/validation.pem")
        }
    }
    def runClient(ArrayList runList) {
        runClient(runListToInitialJson(runList))
    }
    def runClient(HashMap initJson=[:]) {
        configureClient()
        initJson["cloudify"] = context.attributes.thisService["chef"]
        def jsonFile = new File(pathJoin(context.getServiceDirectory(), "chef_client.json"))
        jsonFile.withWriter() { it.write(JsonOutput.toJson(initJson)) }
        sudo("chef-client -j ${jsonFile.getPath()}")
    }
    def runSolo(ArrayList runList) {
        runSolo(runListToInitialJson(runList))
    }
    def runSolo(HashMap initJson=[:], cookbooksUrl=null) {
        def soloConf = new File([context.getServiceDirectory(), "solo.rb"].join(File.separator)).text =
        """
file_cache_path "/tmp/chef-solo"
cookbook_path "/tmp/chef-solo/cookbooks"
        """
        def chef_solo = which("chef-solo")
        assert ! chef_solo.isEmpty()
        def jsonFile = new File(pathJoin(context.getServiceDirectory(), "bootstrap_server.json"))
        jsonFile.text = JsonOutput.toJson(initJson)
        cookbooksUrl = cookbooksUrl ?: chefConfig.bootstrapCookbooksUrl
        sudo("""${chef_solo} -c ${context.getServiceDirectory()}/solo.rb -j ${jsonFile} -r ${cookbooksUrl}""")
    }
    def runListToInitialJson(ArrayList runList) {
        def initJson = [:]
        if (!runList.isEmpty()) {
            initJson["run_list"] = runList
        }
        return initJson
    }
    def fatBinaryInstall() {
        chefBinPath = "/opt/opscode/bin"
        new AntBuilder().sequential {
            mkdir(dir:osConfig.installDir)
            get(src:osConfig.scriptUrl, dest:"${osConfig.installDir}/${osConfig.installer}", skipexisting:true)
            chmod(osfamily:"unix", perm:"0755", file:"${osConfig.installDir}/${osConfig.installer}")
            exec(osfamily:"windows", executable:"msiexec") {
                ["/i", "/q", "${osConfig.installDir}/${osConfig.installer}"].each { arg(value:it) }
            }

        }
        sudo("""${osConfig.installDir}/${osConfig.installer}""")
    }
    def rvm() {
        // not implemented yet
        println "RVM install method is not implemented yet"
    }
    def which(binary) {
        // check for binaries we already know about
        def filename
        if (binary.startsWith("chef-")) {
            filename = pathJoin(getChefBinPath(), binary)
            if (new File(filename).exists()) {
                return filename
            } else {
                return ""
            }
        } else {
            return shellOut("which $binary")
        }
    }
    def getChefBinPath() {
        def path
        switch (chefConfig.installFlavor) {
            case "gem":
                if (! which("gem").isEmpty()) {
                    path = shellOut("gem env").split("\n").find { it =~ "EXECUTABLE DIRECTORY" }.split(":")[1].stripIndent()
                } else { path = "" }
                break
            case "fatBinary":
                path = "/opt/opscode/bin"
                break
            default:
                path = binPath
        }
        return path
    }
}

class DebianBootstrap extends ChefBootstrap {
    def DebianBootstrap(options) { super(options) }
    def rubyPkgs = ["ruby-dev", "ruby", "ruby-json", "rubygems", "libopenssl-ruby"]
    def binPath = "/usr/bin"
    def install_pkgs(List pkgs) {
        sudo("apt-get update")
        sudo("apt-get install -y ${pkgs.join(" ")}", ["DEBIAN_FRONTEND": "noninteractive", "DEBIAN_PRIORITY": "critical"])
    }
    def pkgInstall() {
        sudoWriteFile("/etc/apt/sources.list.d/opscode.list", """
deb http://apt.opscode.com/ ${os.getVendorCodeName().toLowerCase()}-0.10 main
""")
        sudo("wget -O - ${opscode_gpg_key_url} | apt-key add -")
        sudo("apt-get update")
        sudo("""echo "chef chef/chef_server_url string ${chefConfig.serverURL}" | sudo debconf-set-selections""")
        install_pkgs(["opscode-keyring", "chef"])
    }
}

class RHELBootstrap extends ChefBootstrap {
    def RHELBootstrap(options) { super(options) }
    def rubyPkgs = ["ruby", "ruby-devel", "ruby-shadow", "gcc", "gcc-c++", "automake", "autoconf", "make", "curl", "dmidecode"]
    def binPath = "/usr/bin"
    def install(options) {
        if (os.getVendor() in ["CentOS", "Red Hat"]) {
            def shortVersion = os.getVendorVersion().tokenize(".")[0]
            if (shortVersion.toInteger() < 6) { 
                sudo("wget -O /etc/yum.repos.d/aegisco.repo http://rpm.aegisco.com/aegisco/el5/aegisco.repo")
            }
            sudo("rpm -Uvh http://rbel.frameos.org/rbel${shortVersion}")
        }
        return super.install(options)
    }
    def install_pkgs(List pkgs) {
        sudo "yum install -y ${pkgs.join(" ")}"
    }
}

class SuSEBootstrap extends ChefBootstrap {
    def SuSEBootstrap(options) { super(options) }
    def rubyPkgs = ["ruby", "ruby-devel", "ruby-shadow", "gcc", "gcc-c++", "automake", "autoconf", "make", "curl", "dmidecode"]
    def binPath = "/usr/bin"
    def install_pkgs(List pkgs) {
        sudo "zypper install ${pkgs.join(" ")}"
    }
}

class WindowsBootstrap extends ChefBootstrap {
    def WindowsBootstrap(options) { super(options) }
}
