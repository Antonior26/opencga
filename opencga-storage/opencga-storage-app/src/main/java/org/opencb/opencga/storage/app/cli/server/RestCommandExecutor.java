/*
 * Copyright 2015 OpenCB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencb.opencga.storage.app.cli.server;

import org.opencb.opencga.storage.app.cli.CommandExecutor;
import org.opencb.opencga.storage.server.rest.RestStorageServer;

/**
 * Created by imedina on 30/12/15.
 */
public class RestCommandExecutor extends CommandExecutor {

    private ServerCliOptionsParser.RestCommandOptions restCommandOptions;

    public RestCommandExecutor(ServerCliOptionsParser.RestCommandOptions restCommandOptions) {
        this.restCommandOptions = restCommandOptions;
    }

    @Override
    public void execute() throws Exception {
        logger.debug("Executing REST command line");

        String subCommandString = restCommandOptions.getParsedSubCommand();
        switch (subCommandString) {
            case "start":
                init(restCommandOptions.restStartCommandOptions.commonOptions.logLevel,
                        restCommandOptions.restStartCommandOptions.commonOptions.verbose,
                        restCommandOptions.restStartCommandOptions.commonOptions.configFile);
                start();
                break;
            case "stop":
                stop();
                break;
            case "status":
                status();
                break;
            default:
                logger.error("Subcommand not valid");
                break;
        }
    }

    public void start() throws Exception {
        int port = configuration.getServer().getGrpc();
        if (restCommandOptions.restStartCommandOptions.port > 0) {
            port = restCommandOptions.restStartCommandOptions.port;
        }

        // If not --storage-engine is not set then the server will use the default from the storage-configuration.yml
        RestStorageServer server = new RestStorageServer(port);
//        GrpcStorageServer server = new GrpcStorageServer(port, configuration.getDefaultStorageEngineId());
        server.start();
//        server.blockUntilShutdown();
    }

    public void stop() {

    }

    public void status() {

    }

}