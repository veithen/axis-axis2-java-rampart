/*
 * Copyright 2004,2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rampart;

import org.apache.wss4j.common.ext.WSPasswordCallback;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import java.io.IOException;

public class PWCallback implements CallbackHandler {



    /** Field key */

    private static final byte[] key = {

        (byte) 0x31, (byte) 0xfd, (byte) 0xcb, (byte) 0xda, (byte) 0xfb,

        (byte) 0xcd, (byte) 0x6b, (byte) 0xa8, (byte) 0xe6, (byte) 0x19,

        (byte) 0xa7, (byte) 0xbf, (byte) 0x51, (byte) 0xf7, (byte) 0xc7,

        (byte) 0x3e, (byte) 0x80, (byte) 0xae, (byte) 0x98, (byte) 0x51,

        (byte) 0xc8, (byte) 0x51, (byte) 0x34, (byte) 0x04,

    };



    /*

     * (non-Javadoc)

     * @see javax.security.auth.callback.CallbackHandler#handle(javax.security.auth.callback.Callback[])

     */



    /**

     * Method handle

     * 

     * @param callbacks 

     * @throws java.io.IOException                  

     * @throws javax.security.auth.callback.UnsupportedCallbackException 

     */

    public void handle(Callback[] callbacks)

            throws IOException, UnsupportedCallbackException {



        for (int i = 0; i < callbacks.length; i++) {

            if (callbacks[i] instanceof WSPasswordCallback) {

                WSPasswordCallback pc = (WSPasswordCallback) callbacks[i];



                /*

                 * This usage type is used only in case we received a

                 * username token with a password of type PasswordText or

                 * an unknown password type.

                 * 

                 * This case the WSPasswordCallback object contains the

                 * identifier (aka username), the password we received, and

                 * the password type string to identify the type.

                 * 

                 * Here we perform only a very simple check.

                 */

                if (pc.getUsage() == WSPasswordCallback.USERNAME_TOKEN) {

                    if(pc.getIdentifier().equals("Ron") && pc.getPassword().equals("noR")) {

                        return;

                    }
                    
                    if(pc.getIdentifier().equals("joe") && pc.getPassword().equals("eoj")) {

                        return;

                    }
                    
                    if (pc.getPassword().equals("sirhC")) {

                        return;

                    }       
                    
                    if(pc.getIdentifier().equals("alice") && pc.getPassword().equals("password")) {
                        return;
                    }

                    throw new UnsupportedCallbackException(callbacks[i],

                    "check failed");

                }

                /*

                 * here call a function/method to lookup the password for

                 * the given identifier (e.g. a user name or keystore alias)

                 * e.g.: pc.setPassword(passStore.getPassword(pc.getIdentfifier))

                 * for Testing we supply a fixed name here.

                 */

                if (pc.getUsage() == WSPasswordCallback.SECRET_KEY) {

                    pc.setKey(key);

                } else if(pc.getIdentifier().equals("alice")) {

                    pc.setPassword("password");

                } else if(pc.getIdentifier().equals("bob")) {

                    pc.setPassword("password");

                } else if(pc.getIdentifier().equals("Ron")) {

                    pc.setPassword("noR");

                } else if(pc.getIdentifier().equals("joe")) {

                    pc.setPassword("eoj");

                } else if(pc.getIdentifier().equals("ip")) {
                    
                    pc.setPassword("password");
                    
                } else {

                    pc.setPassword("sirhC");

                }

            } else {

                throw new UnsupportedCallbackException(callbacks[i],

                        "Unrecognized Callback");

            }

        }

    }

}
