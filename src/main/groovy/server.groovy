/*
 *  Copyright 2012 Marco Vermeulen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import groovy.text.SimpleTemplateEngine
import org.vertx.groovy.core.http.RouteMatcher

final GVM_VERSION = '@GVM_VERSION@'
final VERTX_VERSION = '@VERTX_VERSION@'
final COLUMN_LENGTH = 15

//
// datasource configuration
//

def config = [
    address: (System.getenv('GVM_DB_ADDRESS') ?: 'mongo-persistor'),
    db_name: (System.getenv('GVM_DB_NAME') ?: 'gvm'),
    host: System.getenv('GVM_DB_HOST'),
    port: System.getenv('GVM_DB_PORT')?.toInteger(),
    username: System.getenv('GVM_DB_USERNAME'),
    password: System.getenv('GVM_DB_PASSWORD')
]

container.deployModule 'vertx.mongo-persistor-v1.2', config

def templateEngine = new SimpleTemplateEngine()


//
// route matcher implementations
//

def rm = new RouteMatcher()

rm.get("/") { req ->
	addPlainTextHeader req
	req.response.sendFile('build/scripts/install.sh')
}

rm.get("/selfupdate") { req ->
	addPlainTextHeader req
	req.response.sendFile('build/scripts/selfupdate.sh')
}

rm.get("/robots.txt") { req ->
    addPlainTextHeader req
    req.response.sendFile('build/resources/main/templates/robots.txt')
}

rm.get("/alive") { req ->
	addPlainTextHeader req
	req.response.end "OK"
}

rm.get("/res") { req ->
    def purpose = req.params['purpose']
	log purpose, 'gvm', GVM_VERSION, req

	def zipFile = 'build/distributions/gvm-scripts.zip' as File
	req.response.putHeader("Content-Type", "application/zip")
	req.response.sendFile zipFile.absolutePath
}

rm.get("/candidates") { req ->
	def cmd = [action:"find", collection:"candidates", matcher:[:], keys:[candidate:1]]
	vertx.eventBus.send("mongo-persistor", cmd){ msg ->
		def candidates = msg.body.results.collect(new TreeSet()) { it.candidate }
		addPlainTextHeader req
		req.response.end candidates.join(',')
	}
}

rm.get("/candidates/:candidate") { req ->
	def candidate = req.params['candidate']
	def cmd = [action:"find", collection:"versions", matcher:[candidate:candidate], keys:["version":1]]
	vertx.eventBus.send("mongo-persistor", cmd){ msg ->
		def response
		if(msg.body.results){
			def versions = msg.body.results.collect(new TreeSet()) { it.version }
			response = versions.join(',')

		} else {
			response = "invalid"
		}

		addPlainTextHeader req
		req.response.end response
	}
}

rm.get("/candidates/:candidate/default") { req ->
	def candidate = req.params['candidate']
	def cmd = [action:"find", collection:"candidates", matcher:[candidate:candidate], keys:["default":1]]
	vertx.eventBus.send("mongo-persistor", cmd){ msg ->
		addPlainTextHeader req
		def defaultVersion = msg.body.results.default
		req.response.end (defaultVersion ?: "")
	}
}

rm.get("/candidates/:candidate/list") { req ->
	def candidate = req.params['candidate']
	def current = req.params['current'] ?: ''
	def installed = req.params['installed'] ? req.params['installed'].tokenize(',') : []

	def cmd = [action:"find", collection:"versions", matcher:[candidate:candidate], keys:["version":1], sort:["version":-1]]
	vertx.eventBus.send("mongo-persistor", cmd){ msg ->
		def available = msg.body.results.collect { it.version }

        def combined = combine(available, installed)
        def local = determineLocal(available, installed)

        def content = prepareListView(combined, current, installed, local, COLUMN_LENGTH)

        def binding = [candidate: candidate, content:content]
        def gtplFile = 'build/templates/list_2.gtpl' as File
        def templateText = templateEngine.createTemplate(gtplFile).make(binding).toString()

        addPlainTextHeader req
		req.response.end templateText
	}
}

private prepareListView(combined, current, installed, local, colLength){
    def output = ""
    for (i in (0..(colLength-1))){
        def versionColumn1 = prepareVersion(combined[i], current, installed, local)
        def versionColumn2 = prepareVersion(combined[i+(colLength*1)], current, installed, local)
        def versionColumn3 = prepareVersion(combined[i+(colLength*2)], current, installed, local)
        def versionColumn4 = prepareVersion(combined[i+(colLength*3)], current, installed, local)
        output += "${pad(versionColumn1)} ${pad(versionColumn2)} ${pad(versionColumn3)} ${pad(versionColumn4)}\n"
    }
    output
}

private prepareVersion(version, current, installed, local){
    def isCurrent = (current == version)
    def isInstalled = installed.contains(version)
    def isLocalOnly = local.contains(version)
    decorateVersion(version, isCurrent, isInstalled, isLocalOnly)
}

private decorateVersion(version, isCurrent, isInstalled, isLocalOnly) {
    " ${markCurrent(isCurrent)} ${markStatus(isInstalled, isLocalOnly)} ${version ?: ''}"
}

private pad(col, width=20) {
    (col ?: "").take(width).padRight(width)
}

private markCurrent(isCurrent){
    isCurrent ? '>' : ' '
}


private markStatus(isInstalled, isLocalOnly){
    if(isInstalled && isLocalOnly) '+'
    else if(isInstalled) '*'
    else ' '
}

private determineLocal(available, installed){
    installed.findAll { ! available.contains(it) }
}

private combine(available, installed){
    def combined = [] as TreeSet
    combined.addAll installed
    combined.addAll available
    combined.toList().reverse()
}

def validationHandler = { req ->
	def candidate = req.params['candidate']
	def version = req.params['version']
	def cmd = [action:"find", collection:"versions", matcher:[candidate:candidate, version:version]]
	vertx.eventBus.send("mongo-persistor", cmd){ msg ->
		addPlainTextHeader req
		if(msg.body.results) {
			req.response.end 'valid'
		} else {
			req.response.end 'invalid'
		}
	}
}

rm.get("/candidates/:candidate/:version/validate", validationHandler)
rm.get("/candidates/:candidate/:version", validationHandler)

def downloadHandler = { req ->
	def candidate = req.params['candidate']
	def version = req.params['version']

	log 'install', candidate, version, req

	def cmd = [action:"find", collection:"versions", matcher:[candidate:candidate, version:version], keys:["url":1]]
	vertx.eventBus.send("mongo-persistor", cmd){ msg ->
		req.response.headers['Location'] = msg.body.results.url.first()
		req.response.statusCode = 302
		req.response.end()
	}
}

rm.get("/candidates/:candidate/:version/download", downloadHandler)
rm.get("/download/:candidate/:version", downloadHandler)

def versionHandler = { req ->
	req.response.end GVM_VERSION
}

rm.get("/app/version", versionHandler)
rm.get("/api/version", versionHandler)

def broadcastHandler = { req ->
	def cmd2 = [action:"find", collection:"broadcast", matcher:[:]]
	vertx.eventBus.send("mongo-persistor", cmd2){ msg ->
		def broadcasts = msg.body.results
        def gtplFile = 'build/templates/broadcast.gtpl' as File
        def binding = [gvmVersion:GVM_VERSION, vertxVersion:VERTX_VERSION, broadcasts:broadcasts]

		def templateText = templateEngine.createTemplate(gtplFile).make(binding).toString()

		addPlainTextHeader req
		req.response.end templateText
	}
}

rm.get("/broadcast", broadcastHandler)
rm.get("/broadcast/:version", broadcastHandler)
rm.get("/api/broadcast", broadcastHandler)
rm.get("/api/broadcast/:version", broadcastHandler)


//
// private methods
//

private addPlainTextHeader(req){
	req.response.putHeader("Content-Type", "text/plain")
}

private log(command, candidate, version, req){
	def date = new Date()
	def host = req.headers['x-forwarded-for']
	def agent = req.headers['user-agent']
	def platform = req.params['platform']

	def document = [
		command:command,
		candidate:candidate,
		version:version,
		host:host,
		agent:agent,
		platform:platform,
		date:date
	]

	def cmd = [action:'save', collection:'audit', document:document] 

	vertx.eventBus.send 'mongo-persistor', cmd
}

//
// startup server
//
def port = System.getenv('PORT') ?: 8080
def host = System.getenv('PORT') ? '0.0.0.0' : 'localhost'
println "Starting vertx on $host:$port"
vertx.createHttpServer().requestHandler(rm.asClosure()).listen(port as int, host)