# ApacheLB 

**Status**: Tested  
**Description**: Apache Load Balancer  
**Maintainer**:       Cloudify  
**Maintainer email**: cloudifysource@gigaspaces.com  
**Contributors**:    [tamirko](https://github.com/tamirko)  
**Homepage**:   [http://www.cloudifysource.org](http://www.cloudifysource.org)  
**License**:      Apache 2.0   
**Build**:   [Cloudify 2.1.1 GA]http://repository.cloudifysource.org/org/cloudifysource/2.1.1/gigaspaces-cloudify-2.1.1-ga-b1400.zip and [Cloudify 2.2.0 M2](http://repository.cloudifysource.org/org/cloudifysource/2.2.0/gigaspaces-cloudify-2.2.0-m2-b2491.zip)   
**Linux* sudoer permissions**:	Mandatory  
**Windows* Admin permissions**:  Not required    
**Release Date**: June 28th 2012  


Tested on:
--------

* <strong>localCloud</strong>: Windows 7 and CentOs 
* <strong>EC2</strong>: Ubuntu and CentOs 
* <strong>OpenStack</strong>: CentOs 
* <strong>Rackspace</strong>: CentOs 

We disable the requiretty flag in /etc/sudoers on the installed VMs, so that Cloudify will be able to invoke remote ssh commands as a sudoer. This feature will be a part of Cloudify in the near future.
Until then, please use the [Cloud Drivers Repository](https://github.com/CloudifySource/cloudify-cloud-drivers).

Synopsis
--------

This folder contains a service recipe for Apache load balancer.

Its default port is 8090, but it can be modified in the apacheLB-service.properties.

You can enable/disable the usage of a sticky session, by modifying the useStickysession property in apacheLB-service.properties.


> *Important*: <strong>In order to use this recipe, the installing user must be a sudoer in the installed VMs.</strong>


## Registering a service instance to the Apache load balancer.

In apacheLB-service.groovy, there are two custom commands: <strong>addNode</strong> and <strong>removeNode</strong>.
You need to add a <strong>postStart</strong> lifecycle event to each service that you want its instances to be able to add themselves to the load balancer as members(nodes).
You need to add a  <strong>postStop</strong> lifecycle event to each service that you want its instances to be able to remove themselves from the load balancer.

<pre><code>

	def ctxPath=("default" == context.applicationName)?"":"${context.applicationName}"

	lifecycle {

		install "myService_install.groovy"
		start "myService_start.groovy"
		....
	    ...
		def instanceID = context.instanceId
		
					
		postStart {			
			def apacheService = context.waitForService("apacheLB", 180, TimeUnit.SECONDS)
			apacheService.invoke("addNode", "http://${InetAddress.localHost.hostAddress}:${port}/${ctxPath}" as String, instanceID as String)
		}
		
		postStop {			
			def apacheService = context.waitForService("apacheLB", 180, TimeUnit.SECONDS)
			apacheService.invoke("removeNode", "http://${InetAddress.localHost.hostAddress}:${port}/${ctxPath}" as String, instanceID as String)			
		}		
	}
</code></pre>  


## Load Testing

In order to test your application under load, you can use the "load" custom command.

<pre><code>
    service {
	
	  name "apacheLB"
	  ...
	  ...
	  
	  customCommands ([
		...
		...
		"load" : "apacheLB-load.groovy"
	  ])
	  
	  ...
	  ...
	}
</code></pre>  

Usage :

The following will fire 35000 requests on http://LB_IP_ADDRESS:LB_PORT/ with 100 concurrent requests each time:

   <strong>invoke apacheLB load 35000 100</strong>


The following will fire 20000 requests on http://LB_IP_ADDRESS:LB_PORT/petclinic with 240 concurrent requests each time:

   <strong>invoke apacheLB load 20000 240 petclinic</strong>




