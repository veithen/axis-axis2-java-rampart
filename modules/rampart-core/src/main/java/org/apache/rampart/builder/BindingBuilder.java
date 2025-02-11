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

package org.apache.rampart.builder;

import org.apache.axiom.om.OMElement;
import org.apache.axis2.addressing.AddressingConstants;
import org.apache.axis2.addressing.AddressingHelper;
import org.apache.axis2.client.Options;
import org.apache.axis2.description.AxisEndpoint;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.rahas.EncryptedKeyToken;
import org.apache.rahas.SimpleTokenStore;
import org.apache.rahas.TrustException;
import org.apache.rampart.RampartException;
import org.apache.rampart.RampartMessageData;
import org.apache.rampart.policy.RampartPolicyData;
import org.apache.rampart.policy.SupportingPolicyData;
import org.apache.rampart.policy.model.RampartConfig;
import org.apache.rampart.policy.model.KerberosConfig;
import org.apache.rampart.util.RampartUtil;
import org.apache.ws.secpolicy.Constants;
import org.apache.ws.secpolicy.SPConstants;
import org.apache.ws.secpolicy.model.AlgorithmSuite;
import org.apache.ws.secpolicy.model.IssuedToken;
import org.apache.ws.secpolicy.model.SecureConversationToken;
import org.apache.ws.secpolicy.model.SupportingToken;
import org.apache.ws.secpolicy.model.Token;
import org.apache.ws.secpolicy.model.UsernameToken;
import org.apache.ws.secpolicy.model.X509Token;
import org.apache.wss4j.common.NamePasswordCallbackHandler;
import org.apache.wss4j.common.WSEncryptionPart;
import org.apache.wss4j.common.derivedKey.ConversationConstants;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.util.KeyUtils;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.engine.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.message.WSSecDKSign;
import org.apache.wss4j.dom.message.WSSecEncryptedKey;
import org.apache.wss4j.dom.message.WSSecHeader;
import org.apache.wss4j.dom.message.WSSecSignature;
import org.apache.wss4j.dom.message.WSSecSignatureConfirmation;
import org.apache.wss4j.dom.message.WSSecTimestamp;
import org.apache.wss4j.dom.message.WSSecUsernameToken;
import org.apache.wss4j.dom.message.token.KerberosSecurity;
import org.apache.wss4j.common.token.SecurityTokenReference;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.xml.crypto.dsig.Reference;

import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public abstract class BindingBuilder {
    private static Log log = LogFactory.getLog(BindingBuilder.class);

    private Element insertionLocation;
    
    protected String mainSigId = null;
    
    protected ArrayList<String> encryptedTokensIdList = new ArrayList<String>();
    
    protected Element timestampElement;
    
    protected Element mainRefListElement;
    
    
    /**
     * @param rmd RampartMessageData
     */
    protected void addTimestamp(RampartMessageData rmd) {
        log.debug("Adding timestamp");

        WSSecTimestamp timestampBuilder = new WSSecTimestamp(rmd.getSecHeader());

        timestampBuilder.setTimeToLive(RampartUtil.getTimeToLive(rmd));
        
        // add the Timestamp to the SOAP Enevelope

        timestampBuilder.build();

        if (log.isDebugEnabled()) {
            log.debug("Timestamp id: " + timestampBuilder.getId());
        }
        rmd.setTimestampId(timestampBuilder.getId());
        
        this.timestampElement = timestampBuilder.getElement();
        log.debug("Adding timestamp: DONE");
    }
    
    /**
     * Add a UsernameToken to the security header
     * @param rmd RampartMessageData
     * @param token Rampart token
     * @return The <code>WSSecUsernameToken</code> instance
     * @throws RampartException If an error occurred during the addition of a UsernameToken Object
     */
    protected WSSecUsernameToken addUsernameToken(RampartMessageData rmd, UsernameToken token) throws RampartException {

        log.debug("Adding a UsernameToken");

        RampartPolicyData rpd = rmd.getPolicyData();
        
        //Get the user
        //First try options
        Options options = rmd.getMsgContext().getOptions();
        String user = options.getUserName();
        if(user == null || user.length() == 0) {
            //Then try RampartConfig
            if(rpd.getRampartConfig() != null) {
                user = rpd.getRampartConfig().getUser();
            }
        }
        
        if(user != null && !"".equals(user)) {
            if (log.isDebugEnabled()) {
                log.debug("User : " + user);
            }

            // If NoPassword property is set we don't need to set the password
            if (token.isNoPassword()) {
                WSSecUsernameToken utBuilder = new WSSecUsernameToken(rmd.getSecHeader());
                utBuilder.setUserInfo(user, null);
                utBuilder.setPasswordType(null);
                return utBuilder;
            }
            
            //Get the password

            //First check options object for a password
            String password = options.getPassword();
            
            if(password == null || password.length() == 0) {
                
                //Then try to get the password from the given callback handler
                CallbackHandler handler = RampartUtil.getPasswordCB(rmd);
            
                if(handler == null) {
                    //If the callback handler is missing
                    throw new RampartException("cbHandlerMissing");
                }
                
                WSPasswordCallback[] cb = { new WSPasswordCallback(user,
                        WSPasswordCallback.USERNAME_TOKEN) };
                try {
                    handler.handle(cb);
                } catch (Exception e) {
                    throw new RampartException("errorInGettingPasswordForUser", 
                            new String[]{user}, e);
                }
                
                //get the password
                password = cb[0].getPassword();
            }

            if(password != null && !"".equals(password)) {
                //If the password is available then build the token
                
                WSSecUsernameToken utBuilder = new WSSecUsernameToken(rmd.getSecHeader());
                if (token.isHashPassword()) {
                    utBuilder.setPasswordType(WSConstants.PASSWORD_DIGEST);  
                } else {
                    utBuilder.setPasswordType(WSConstants.PASSWORD_TEXT);
                }
                
                utBuilder.setUserInfo(user, password);
                
                return utBuilder;
            } else {
                //If there's no password then throw an exception
                throw new RampartException("noPasswordForUser", 
                        new String[]{user});
            }
            
        } else {
            log.debug("No user value specified in the configuration");
            throw new RampartException("userMissing");
        }
        
    }
    
    
    /**
     * @param rmd RampartMessageData
     * @param token Identifier
     * @return WSSecEncryptedKey encrypted key
     * @throws RampartException If an error occurred getting the WSSecEncryptedKey
     */
    protected WSSecEncryptedKey getEncryptedKeyBuilder(RampartMessageData rmd, Token token, SecretKey symmetricKey) throws RampartException {
        
        RampartPolicyData rpd = rmd.getPolicyData();
        Document doc = rmd.getDocument();
        
        WSSecEncryptedKey encrKey = new WSSecEncryptedKey(rmd.getSecHeader());
        
        try {
            RampartUtil.setKeyIdentifierType(rmd, encrKey, token);
            RampartUtil.setEncryptionUser(rmd, encrKey);

            //TODO we do not need to pass keysize as it is taken from algorithm it self - verify
            encrKey.setKeyEncAlgo(rpd.getAlgorithmSuite().getAsymmetricKeyWrap());
            
            log.debug("getEncryptedKeyBuilder() setting KeyEncAlgo to value: " + rpd.getAlgorithmSuite().getAsymmetricKeyWrap());
            encrKey.prepare(RampartUtil.getEncryptionCrypto(rpd.getRampartConfig(), rmd.getCustomClassLoader()), symmetricKey);
            
            return encrKey;
        } catch (WSSecurityException e) {
            throw new RampartException("errorCreatingEncryptedKey", e);
        }
    }
    
    protected WSSecSignature getSignatureBuilder(RampartMessageData rmd, 
                                                 Token token)throws RampartException {
        return getSignatureBuilder(rmd, token, null);
    }
    
    protected WSSecSignature getSignatureBuilder(RampartMessageData rmd, Token token,
                                                 String userCertAlias) throws RampartException {

        RampartPolicyData rpd = rmd.getPolicyData();
        
        WSSecSignature sig = new WSSecSignature(rmd.getSecHeader());
        checkForX509PkiPath(sig, token);

        if (log.isDebugEnabled()) {
            log.debug("Token inclusion: " + token.getInclusion());
        }

        RampartUtil.setKeyIdentifierType(rmd, sig, token);

        String user = null;
        
        if (userCertAlias != null) {
            user = userCertAlias;
        }

        // Get the user - First check whether userCertAlias present
        RampartConfig rampartConfig = rpd.getRampartConfig();
        if(rampartConfig == null) {
        	throw new RampartException("rampartConfigMissing");
        }
        
		if (user == null) {
            user = rampartConfig.getUserCertAlias();
        }
        
        // If userCertAlias is not present, use user property as Alias
        
        if (user == null) {
            user = rampartConfig.getUser();
        }
            
        String password = null;

        if(user != null && !"".equals(user)) {
            if (log.isDebugEnabled()) {
                log.debug("User : " + user);
            }

            //Get the password
            CallbackHandler handler = RampartUtil.getPasswordCB(rmd);
            
            if(handler == null) {
                //If the callback handler is missing
                throw new RampartException("cbHandlerMissing");
            }
            
            WSPasswordCallback[] cb = { new WSPasswordCallback(user,
                    WSPasswordCallback.SIGNATURE) };
            
            try {
                handler.handle(cb);
                if(cb[0].getPassword() != null && !"".equals(cb[0].getPassword())) {
                    password = cb[0].getPassword();
                    if (log.isDebugEnabled()) {
                        log.debug("Password : " + password);
                    }
                } else {
                    //If there's no password then throw an exception
                    throw new RampartException("noPasswordForUser", 
                            new String[]{user});
                }
            } catch (IOException e) {
                throw new RampartException("errorInGettingPasswordForUser", 
                        new String[]{user}, e);
            } catch (UnsupportedCallbackException e) {
                throw new RampartException("errorInGettingPasswordForUser", 
                        new String[]{user}, e);
            }
            
        } else {
            log.debug("No user value specified in the configuration");
            throw new RampartException("userMissing");
        }
        
        sig.setUserInfo(user, password);
        AlgorithmSuite algorithmSuite = rpd.getAlgorithmSuite();
		sig.setSignatureAlgorithm(algorithmSuite.getAsymmetricSignature());
        sig.setSigCanonicalization(algorithmSuite.getInclusiveC14n());
        sig.setDigestAlgo(algorithmSuite.getDigest());

        try {
            sig.prepare(RampartUtil.getSignatureCrypto(rampartConfig, rmd.getCustomClassLoader()));
        } catch (WSSecurityException e) {
            throw new RampartException("errorInSignatureWithX509Token", e);
        }
        
        return sig;
    }
    
    /**
     * @param rmd RampartMessageData
     * @param suppTokens SupportingToken
     * @throws RampartException If an error occurred processing the SupportingToken Object
     * @return HashMap
     */
    protected HashMap<Token,Object> handleSupportingTokens(RampartMessageData rmd, SupportingToken suppTokens)
            throws RampartException {
        
        //Create the list to hold the tokens
        // TODO putting different types of objects. Need to figure out a way to add single types of objects
        HashMap<Token,Object> endSuppTokMap = new HashMap<Token,Object>();
        
        if(suppTokens != null && suppTokens.getTokens() != null &&
                suppTokens.getTokens().size() > 0) {
            log.debug("Processing supporting tokens");

            for (Token token : suppTokens.getTokens()) {
                org.apache.rahas.Token endSuppTok = null;
                if (token instanceof IssuedToken && rmd.isInitiator()) {
                    String id = RampartUtil.getIssuedToken(rmd, (IssuedToken) token);
                    try {
                        endSuppTok = rmd.getTokenStorage().getToken(id);
                    } catch (TrustException e) {
                        throw new RampartException("errorInRetrievingTokenId",
                                new String[]{id}, e);
                    }

                    if (endSuppTok == null) {
                        throw new RampartException("errorInRetrievingTokenId",
                                new String[]{id});
                    }

                    //Add the token to the header
                    Element siblingElem = RampartUtil
                            .insertSiblingAfter(rmd, this.getInsertionLocation(),
                                    (Element) endSuppTok.getToken());
                    this.setInsertionLocation(siblingElem);

                    if (suppTokens.isEncryptedToken()) {
                        this.encryptedTokensIdList.add(endSuppTok.getId());
                    }

                    //Add the extracted token
                    endSuppTokMap.put(token, endSuppTok);

                } else if (token instanceof X509Token) {

                    //We have to use a cert
                    //Prepare X509 signature
                    WSSecSignature sig = this.getSignatureBuilder(rmd, token);
                    Element bstElem = sig.getBinarySecurityTokenElement();
                    if (bstElem != null) {
                        bstElem = RampartUtil.insertSiblingAfter(rmd,
                                this.getInsertionLocation(), bstElem);
                        this.setInsertionLocation(bstElem);

                        SupportingPolicyData supportingPolcy = new SupportingPolicyData();
                        supportingPolcy.build(suppTokens);
                        supportingPolcy.setSignatureToken(token);
                        supportingPolcy.setEncryptionToken(token);
                        rmd.getPolicyData().addSupportingPolicyData(supportingPolcy);

                        if (suppTokens.isEncryptedToken()) {
                            this.encryptedTokensIdList.add(sig.getBSTTokenId());
                        }
                    }
                    endSuppTokMap.put(token, sig);

                } else if (token instanceof UsernameToken) {
                    WSSecUsernameToken utBuilder = addUsernameToken(rmd, (UsernameToken) token);

                    utBuilder.prepare();

                    //Add the UT
                    Element elem = utBuilder.getUsernameTokenElement();
                    elem = RampartUtil.insertSiblingAfter(rmd, this.getInsertionLocation(), elem);
                    
                    if (suppTokens.isEncryptedToken()) {
                    	encryptedTokensIdList.add(utBuilder.getId());
                    }

                    //Move the insert location to the next element
                    this.setInsertionLocation(elem);
                    Date now = new Date();
                    try {
                        org.apache.rahas.Token tempTok = new org.apache.rahas.Token(
                                utBuilder.getId(), (OMElement) elem, now,
                                new Date(now.getTime() + 300000));
                        endSuppTokMap.put(token, tempTok);
                    } catch (TrustException e) {
                        throw new RampartException("errorCreatingRahasToken", e);
                    }
                }
            }
        }
        
        return endSuppTokMap;
    }
    /**
     * @param tokenMap the tokenMap
     * @param sigParts the sigParts
     * @return List from the sigParts
     * @throws RampartException If an error occurred processing the Signature Parts
     */
    protected List<WSEncryptionPart> addSignatureParts(HashMap tokenMap, List<WSEncryptionPart> sigParts)
            throws RampartException {
    	
        Set entrySet = tokenMap.entrySet();

        for (Object anEntrySet : entrySet) {
            Object tempTok = ((Entry) anEntrySet).getValue();
            WSEncryptionPart part = null;

            if (tempTok instanceof org.apache.rahas.Token) {

                part = new WSEncryptionPart(
                        ((org.apache.rahas.Token) tempTok).getId());

            } else if (tempTok instanceof WSSecSignature) {
                WSSecSignature tempSig = (WSSecSignature) tempTok;
                if (tempSig.getBSTTokenId() != null) {
                    part = new WSEncryptionPart(tempSig.getBSTTokenId());
                }
            } else {

                throw new RampartException("UnsupportedTokenInSupportingToken");
            }
            sigParts.add(part);
        }
                
        return sigParts;
    }

    
    public Element getInsertionLocation() {
        return insertionLocation;
    }

    public void setInsertionLocation(Element insertionLocation) {
        this.insertionLocation = insertionLocation;
    }
    
    
    protected List<byte[]> doEndorsedSignatures(RampartMessageData rmd, HashMap<Token,Object> tokenMap) throws RampartException {
        
        List<byte[]> sigValues = new ArrayList<byte[]>();

        for (Map.Entry<Token,Object> entry : tokenMap.entrySet()) {
            Token token = entry.getKey();
            Object tempTok = entry.getValue();

            // Migrating to a list
            List<WSEncryptionPart> sigParts = new ArrayList<WSEncryptionPart>();
            sigParts.add(new WSEncryptionPart(this.mainSigId));

            if (tempTok instanceof org.apache.rahas.Token) {
                org.apache.rahas.Token tok = (org.apache.rahas.Token) tempTok;
                if (rmd.getPolicyData().isTokenProtection()) {
                    sigParts.add(new WSEncryptionPart(tok.getId()));
                }

                this.doSymmSignature(rmd, token, (org.apache.rahas.Token) tempTok, sigParts);

            } else if (tempTok instanceof WSSecSignature) {
                WSSecSignature sig = (WSSecSignature) tempTok;
                if (rmd.getPolicyData().isTokenProtection() &&
                        sig.getBSTTokenId() != null) {
                    sigParts.add(new WSEncryptionPart(sig.getBSTTokenId()));
                }

                try {


                    List<Reference> referenceList
                            = sig.addReferencesToSign(sigParts);

                    /**
                     * Before migration it was - this.setInsertionLocation(RampartUtil.insertSiblingAfter(rmd, this
                     *       .getInsertionLocation(), supportingSignatureElement));
                     *
                     * In this case we need to append <Signature>..</Signature> element to
                     * current insertion location
                     */

                    sig.computeSignature(referenceList, false, this.getInsertionLocation());

                    this.setInsertionLocation(sig.getSignatureElement());

                } catch (WSSecurityException e) {
                    throw new RampartException("errorInSignatureWithX509Token", e);
                }
                sigValues.add(sig.getSignatureValue());
            }
        } 

        return sigValues;
            
    }
    
    
    protected byte[] doSymmSignature(RampartMessageData rmd, Token policyToken, org.apache.rahas.Token tok,
                                     List<WSEncryptionPart> sigParts) throws RampartException {
        
        Document doc = rmd.getDocument();
        
        RampartPolicyData rpd = rmd.getPolicyData();
        
        AlgorithmSuite algorithmSuite = rpd.getAlgorithmSuite();
		if(policyToken.isDerivedKeys()) {
            try {
                WSSecDKSign dkSign = new WSSecDKSign(rmd.getSecHeader());  
                
                //Check whether it is security policy 1.2 and use the secure conversation accordingly
                if (SPConstants.SP_V12 == policyToken.getVersion()) {
                    dkSign.setWscVersion(ConversationConstants.VERSION_05_12);
                }
                              
                //Check for whether the token is attached in the message or not
                boolean attached = false;
                
                if (SPConstants.INCLUDE_TOEKN_ALWAYS == policyToken.getInclusion() ||
                    SPConstants.INCLUDE_TOKEN_ONCE == policyToken.getInclusion() ||
                    (rmd.isInitiator() && SPConstants.INCLUDE_TOEKN_ALWAYS_TO_RECIPIENT 
                            == policyToken.getInclusion())) {
                    attached = true;
                }
                
                // Setting the AttachedReference or the UnattachedReference according to the flag
                OMElement ref;
                if (attached) {
                    ref = tok.getAttachedReference();
                } else {
                    ref = tok.getUnattachedReference();
                }
                
                if(ref != null) {
                    dkSign.setStrElem((Element) doc.importNode((Element) ref, true));
                    dkSign.prepare(tok.getSecret());
                } else if (!rmd.isInitiator() && policyToken.isDerivedKeys()) { 
                	
                	// If the Encrypted key used to create the derived key is not
                	// attached use key identifier as defined in WSS1.1 section
                	// 7.7 Encrypted Key reference
                	SecurityTokenReference tokenRef = new SecurityTokenReference(doc);
                	if(tok instanceof EncryptedKeyToken) {
                	    tokenRef.setKeyIdentifierEncKeySHA1(((EncryptedKeyToken)tok).getSHA1());;
                	}
                        dkSign.setStrElem(tokenRef.getElement());
                        dkSign.prepare(tok.getSecret());
                    tokenRef.addTokenType(WSConstants.WSS_ENC_KEY_VALUE_TYPE);  // TODO check this
                
                } else {
                    dkSign.setTokenIdentifier(tok.getId());
                    dkSign.prepare(tok.getSecret());
                }

                //Set the algo info
                dkSign.setSignatureAlgorithm(algorithmSuite.getSymmetricSignature());
                dkSign.setDerivedKeyLength(algorithmSuite.getSignatureDerivedKeyLength()/8);
//                dkSign.setDigestAlgorithm(algorithmSuite.getDigest()); //uncomment when wss4j version is updated
                if(tok instanceof EncryptedKeyToken) {
                    //Set the value type of the reference
                    dkSign.setCustomValueType(WSConstants.SOAPMESSAGE_NS11 + "#"
                        + WSConstants.ENC_KEY_VALUE_TYPE);
                }
                
                if(rpd.isTokenProtection()) {

                    //Hack to handle reference id issues
                    //TODO Need a better fix
                    String sigTokId = tok.getId();
                    if(sigTokId.startsWith("#")) {
                        sigTokId = sigTokId.substring(1);
                    }
                    sigParts.add(new WSEncryptionPart(sigTokId));
                }
                
                dkSign.getParts().addAll(sigParts); 
                
                List<Reference> referenceList
                        = dkSign.addReferencesToSign(sigParts);

                //Add elements to header
                //Do signature
                if (rpd.getProtectionOrder().equals(SPConstants.ENCRYPT_BEFORE_SIGNING) &&
                        this.mainRefListElement != null ) {

                     /**
                     * <xenc:ReferenceList>
                     *     <xenc:DataReference URI="#EncDataId-2"/>
                     * </xenc:ReferenceList>
                     * If there is a reference list as above we need to first prepend reference list
                     * with the new derived key. Then we need to prepend Signature to newly added derived key.
                     */

                    // Add DeriveKey before ReferenceList
                    RampartUtil.insertSiblingBefore(rmd, this.mainRefListElement, dkSign.getdktElement());

                    // Insert signature before DerivedKey
                    dkSign.computeSignature(referenceList, true, dkSign.getdktElement());
                    this.setInsertionLocation(this.mainRefListElement);
                } else {

                    /**
                     * Add <wsc:DerivedKeyToken>..</wsc:DerivedKeyToken> to security
                     * header.
                     */
                    dkSign.appendDKElementToHeader();

                    this.setInsertionLocation(dkSign.getdktElement());

                    /**
                     * In this case we need to insert <Signature>..</Signature> element
                     * before this.mainRefListElement element. In other words we need to
                     * prepend <Signature>...</Signature> element to this.mainRefListElement.
                     */
                    dkSign.computeSignature(referenceList, false, this.getInsertionLocation());
                    this.setInsertionLocation(dkSign.getSignatureElement());
                }

                return dkSign.getSignatureValue();
                
            } catch (WSSecurityException e) {
                throw new RampartException(
                        "errorInDerivedKeyTokenSignature", e);
            }
        } else {
            try {
                WSSecSignature sig = new WSSecSignature(rmd.getSecHeader());
                
                // If a EncryptedKeyToken is used, set the correct value type to
                // be used in the wsse:Reference in ds:KeyInfo
                if (policyToken instanceof X509Token) {
                    if (rmd.isInitiator()) {
                        sig.setCustomTokenValueType(WSConstants.SOAPMESSAGE_NS11 + "#"
                                + WSConstants.ENC_KEY_VALUE_TYPE);
                        sig.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
                    } else {
                        // the tok has to be an EncryptedKey token
                        sig.setEncrKeySha1value(((EncryptedKeyToken) tok).getSHA1());
                        sig.setKeyIdentifierType(WSConstants.ENCRYPTED_KEY_SHA1_IDENTIFIER);
                    }

                } else if (policyToken instanceof IssuedToken) {

                    sig.setCustomTokenValueType(RampartUtil.getSAML10AssertionNamespace());
                    sig.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
                }
                
                String sigTokId; 
                
                if ( policyToken instanceof SecureConversationToken) {
                    sig.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
                    OMElement ref = tok.getAttachedReference();
                    if(ref == null) {
                        ref = tok.getUnattachedReference();
                    }
                    
                    if (ref != null) {
                        sigTokId = SimpleTokenStore.getIdFromSTR(ref);
                    } else {
                        sigTokId = tok.getId();
                    }
                } else {
                    sigTokId = tok.getId();
                }
                               
                //Hack to handle reference id issues
                //TODO Need a better fix
                if(sigTokId.startsWith("#")) {
                    sigTokId = sigTokId.substring(1);
                }
                
                sig.setCustomTokenId(sigTokId);
                sig.setSecretKey(tok.getSecret());
                sig.setSignatureAlgorithm(algorithmSuite.getAsymmetricSignature()); // TODO what is the correct algorith ? For sure one is redundant
                sig.setSignatureAlgorithm(algorithmSuite.getSymmetricSignature());
                sig.setDigestAlgo(algorithmSuite.getDigest());
                sig.prepare(RampartUtil.getSignatureCrypto(rpd.getRampartConfig(), rmd.getCustomClassLoader()));

                sig.getParts().addAll(sigParts); 
                List<Reference> referenceList
                        = sig.addReferencesToSign(sigParts);

                //Do signature
                if (rpd.getProtectionOrder().equals(SPConstants.ENCRYPT_BEFORE_SIGNING)
                        && this.mainRefListElement != null) {

                    /**
                     * In this case we need to insert <Signature>..</Signature> element
                     * before this.mainRefListElement element. In other words we need to
                     * prepend <Signature>...</Signature> element to this.mainRefListElement.
                     * this.mainRefListElement is equivalent to
                     * <xenc:ReferenceList>
                     *     <xenc:DataReference URI="#EncDataId-2"/>
                     * </xenc:ReferenceList>
                     */
                    sig.computeSignature(referenceList, true, this.mainRefListElement);
                    this.setInsertionLocation(this.mainRefListElement);
                } else {

                    /**
                     * In this case we need to append <Signature>..</Signature> element to
                     * current insertion location.
                     */
                    sig.computeSignature(referenceList, false, this.getInsertionLocation());
                    this.setInsertionLocation(sig.getSignatureElement());
                }


                return sig.getSignatureValue();
                
            } catch (WSSecurityException e) {
                throw new RampartException("errorInSignatureWithACustomToken", e);
            }

        }
    }
    
    
    /**
     * Get hold of the token from the token storage
     * @param rmd RampartMessageData
     * @param tokenId Identifier
     * @return token from the token storage
     * @throws RampartException If an error occurred getting the Token
     */
    protected org.apache.rahas.Token getToken(RampartMessageData rmd, 
                    String tokenId) throws RampartException {
        org.apache.rahas.Token tok;
        try {
            tok = rmd.getTokenStorage().getToken(tokenId);
        } catch (TrustException e) {
            throw new RampartException("errorInRetrievingTokenId", 
                    new String[]{tokenId}, e);
        }
        
        if(tok == null) {
            throw new RampartException("errorInRetrievingTokenId", 
                    new String[]{tokenId});
        }
        return tok;
    }
    

    protected void addSignatureConfirmation(RampartMessageData rmd, List<WSEncryptionPart> sigParts) {
        
        if(!rmd.getPolicyData().isSignatureConfirmation()) {
            
            //If we don't require sig confirmation simply go back :-)
            return;
        }
        
        Document doc = rmd.getDocument();

        List<WSHandlerResult> results
                = (List<WSHandlerResult>)rmd.getMsgContext().getProperty(WSHandlerConstants.RECV_RESULTS);
        /*
         * loop over all results gathered by all handlers in the chain. For each
         * handler result get the various actions. After that loop we have all
         * signature results in the signatureActions list.
         */
        List<WSSecurityEngineResult> signatureActions = new ArrayList<WSSecurityEngineResult>();
        for (Object result : results) {
            WSHandlerResult wshResult = (WSHandlerResult) result;

            signatureActions.addAll(wshResult.getActionResults().get(WSConstants.SIGN));
            signatureActions.addAll(wshResult.getActionResults().get(WSConstants.ST_SIGNED));
            signatureActions.addAll(wshResult.getActionResults().get(WSConstants.UT_SIGN));
        }
        
        // prepare a SignatureConfirmation token
        WSSecSignatureConfirmation wsc = new WSSecSignatureConfirmation(doc);
        if (signatureActions.size() > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Signature Confirmation: number of Signature results: "
                        + signatureActions.size());
            }
            for (WSSecurityEngineResult signatureAction : signatureActions) {
                byte[] sigVal = (byte[]) signatureAction.get(WSSecurityEngineResult.TAG_SIGNATURE_VALUE);
                wsc.setSignatureValue(sigVal);
                wsc.prepare();
                RampartUtil.appendChildToSecHeader(rmd, wsc.getSignatureConfirmationElement());
                if (sigParts != null) {
                    sigParts.add(new WSEncryptionPart(wsc.getId()));
                }
            }
        } else {
            //No Sig value
            wsc.prepare();
            RampartUtil.appendChildToSecHeader(rmd, wsc.getSignatureConfirmationElement());
            if(sigParts != null) {
                sigParts.add(new WSEncryptionPart(wsc.getId()));
            }
        }
    }
    private void checkForX509PkiPath(WSSecSignature sig, Token token){
        if (token instanceof X509Token) {
            X509Token x509Token = (X509Token) token;
            if (x509Token.getTokenVersionAndType().equals(Constants.WSS_X509_PKI_PATH_V1_TOKEN10)
                    || x509Token.getTokenVersionAndType().equals(Constants.WSS_X509_PKI_PATH_V1_TOKEN11)) {
                sig.setUseSingleCertificate(false);
            }
        }
    }

    protected KerberosSecurity addKerberosToken(RampartMessageData rmd, Token token)
            throws RampartException {
        RampartPolicyData rpd = rmd.getPolicyData();
        KerberosConfig krbConfig = rpd.getRampartConfig().getKerberosConfig();

        if (krbConfig == null) {
            throw new RampartException("noKerberosConfigDefined");
        }

        log.debug("Token inclusion: " + token.getInclusion());

        String user = krbConfig.getPrincipalName();
        if (user == null) {
            user = rpd.getRampartConfig().getUser();
        }
        
        String password = krbConfig.getPrincipalPassword();
        if (password == null) {
            CallbackHandler handler = RampartUtil.getPasswordCB(rmd);

            if (handler != null) {
                if (user == null) {
                    log.debug("Password callback is configured but no user value is specified in the configuration");
                    throw new RampartException("userMissing");
                }
                
                //TODO We do not have a separate usage type for Kerberos token, let's use custom token
                WSPasswordCallback[] cb = { new WSPasswordCallback(user, WSPasswordCallback.CUSTOM_TOKEN) };
                try {
                    handler.handle(cb);
                    if (cb[0].getPassword() != null && !"".equals(cb[0].getPassword())) {
                        password = cb[0].getPassword();
                    }
                } catch (IOException e) {
                    throw new RampartException("errorInGettingPasswordForUser", new String[] { user }, e);
                } catch (UnsupportedCallbackException e) {
                    throw new RampartException("errorInGettingPasswordForUser", new String[] { user }, e);
                }
            }
        }
        
        String principalName = null;
        boolean isUsernameServiceNameForm = KerberosConfig.USERNAME_NAME_FORM.equals(krbConfig.getServicePrincipalNameForm());
        
        AxisEndpoint endpoint = rmd.getMsgContext().findEndpoint();
        if (endpoint != null) {
            if (log.isDebugEnabled()) {
                log.debug("Identified endpoint: " + endpoint.getName() + ". Looking for SPN identity claim.");
            }
            
            OMElement addressingIdentity = AddressingHelper.getAddressingIdentityParameterValue(endpoint);
            if (addressingIdentity != null) {
                OMElement spnClaim = addressingIdentity.getFirstChildWithName(AddressingConstants.QNAME_IDENTITY_SPN);
                if (spnClaim != null) {
                    principalName = spnClaim.getText();
                    isUsernameServiceNameForm = false;
                    if (log.isDebugEnabled()) {
                        log.debug("Found SPN identity claim: " + principalName);
                    }
                }
                else {
                    OMElement upnClaim = addressingIdentity.getFirstChildWithName(AddressingConstants.QNAME_IDENTITY_UPN);
                    if (upnClaim != null) {
                        principalName = upnClaim.getText();
                        isUsernameServiceNameForm = true;
                        if (log.isDebugEnabled()) {
                            log.debug("Found UPN identity claim: " + principalName);
                        }
                    } else if (log.isDebugEnabled()) {
                        log.debug(String.format("Neither SPN nor UPN identity claim found in %s EPR element for endpoint %s.", addressingIdentity.getQName().toString(), endpoint.getName()));
                    }
                }
            }
        }
        
        if (principalName == null) {
        	principalName = krbConfig.getServicePrincipalName();
        }
        
        try {
            KerberosSecurity bst = new KerberosSecurity(rmd.getDocument());
            
            NamePasswordCallbackHandler cb = new NamePasswordCallbackHandler(user, password);
            bst.retrieveServiceTicket(krbConfig.getJaasContext(), cb, principalName, isUsernameServiceNameForm,
                krbConfig.isRequstCredentialDelegation(), krbConfig.getDelegationCredential());
            
            return bst;
        } catch (WSSecurityException e) {
            throw new RampartException("errorInBuildingKereberosToken", e);
        }
    }
}
