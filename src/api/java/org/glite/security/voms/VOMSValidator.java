/*
 * Copyright (c) Members of the EGEE Collaboration. 2004.
 * See http://eu-egee.org/partners/ for details on the copyright holders.
 * For license conditions see the license file or http://eu-egee.org/license.html
 */

package org.glite.security.voms;

import java.io.ByteArrayInputStream;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.StringTokenizer;
import java.util.Vector;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERInputStream;
import org.bouncycastle.asn1.DEROctetString;
import org.glite.security.voms.ac.ACTrustStore;
import org.glite.security.voms.ac.ACValidator;
import org.glite.security.voms.ac.AttributeCertificate;

import org.glite.security.voms.peers.VomsdataPeer;
import org.glite.security.voms.peers.VomsPeer;

/**
 * Reads a DER-encode, Base64-encoded, or PEM-encoded certificate from disk
 * without using broken IAIK implementations...
 *
 * @author mulmo
 */
class CertUtil {
    /** log4j util for logging */
    static Logger logger = Logger.getLogger(CertUtil.class.getName());

    /**
     * Finds out the index of the client cert in a certificate chain.
     * @param X509Certificate[] the cert chain
     * @return the index of the client cert of -1 if no client cert was
     * found
     */
    public static int findClientCert(X509Certificate[] chain) {
        int i;
        // get the index for the first cert that isn't a CA or proxy cert
        for (i = chain.length-1 ; i >= 0 ; i--) {
            // if constrainCheck = -1 the cert is NOT a CA cert
            if (chain[i].getBasicConstraints() == -1) {
                // double check, if issuerDN = subjectDN the cert is CA
                if (!chain[i].getIssuerDN().equals(chain[i].getSubjectDN())) {
                    break;
                }
            }
        }

        // no valid client certs found, print an error message?
        if (i == chain.length) {
            logger.error("UpdatingKeymanager: invalid certificate chain, client cert missing.");

            return -1;
        } else {
            return i;
        }
    }
}

/**
 * The main (top) class to use for extracting VOMS information from
 * a certificate and/or certificate chain. The VOMS information can
 * simply be parsed or validated. No validation is performed on the
 * certificate chain -- that is assumed to already have happened.
 * <br>
 * The certificate chain is assumed to already be validated. It is
 * also assumed to be sorted in TLS order, that is certificate
 * issued by trust anchor first and client certificate last.
 * <br>
 * Example of use: this will validate any VOMS attributes in the
 * certificate chain and check if any of the attributes grants the
 * user the "admin" role in the group (VO) "MyVO".
 * <pre>
 * boolean isAdmin = new VOMSValidator(certChain).validate().getRoles("MyVO").contains("admin");
 * </pre>
 *
 * @author mulmo
 */
public class VOMSValidator {
    static Logger log = Logger.getLogger(VOMSValidator.class);
    public static final String VOMS_EXT_OID = "1.3.6.1.4.1.8005.100.100.5";
    protected static ACTrustStore theTrustStore;
    protected ACValidator myValidator;
    protected X509Certificate[] myValidatedChain;
    protected Vector myVomsAttributes = new Vector();
    protected boolean isParsed = false;
    protected boolean isValidated = false;
    protected boolean isPreValidated = false;
    protected FQANTree myFQANTree = null;
    private VomsdataPeer vp = null;
    
    /**
     * Convenience constructor in the case where you have a single
     * cert and not a chain.
     * @param cert
     * @see #VOMSValidator(X509Certificate[])
     */
    public VOMSValidator(X509Certificate validatedCert) {
        this(new X509Certificate[] { validatedCert });
    }

    /**
     * Convenience constructor<br>
     * Same as <code>VOMSValidator(validatedChain, null)</code>
     * @param validatedChain
     */
    public VOMSValidator(X509Certificate[] validatedChain) {
        this(validatedChain, null);
    }

    /**
     * If <code>validatedChain</code> is <code>null</code>, a call to
     * <code>setValidatedChain()</code> MUST be made before calling
     * <code>parse()</code> or <code>validate()</code>.
     *
     * @param validatedChain The (full), validated certificate chain
     * @param acValidator The AC validator implementation to use (null is default with a BasicVOMSTrustStore)
     *
     * @see org.glite.security.voms.ac.ACValidator
     * @see BasicVOMSTrustStore
     */
    public VOMSValidator(X509Certificate[] validatedChain, ACValidator acValidator) {
        myValidatedChain = validatedChain; // allow null

        if (acValidator == null) {
            if (theTrustStore == null) {
                theTrustStore = new BasicVOMSTrustStore();
            }
        }

        myValidator = (acValidator == null) ? new ACValidator(theTrustStore) : acValidator;
        vp = new VomsdataPeer();
        isPreValidated = vp.Retrieve(validatedChain[0], validatedChain, VomsdataPeer.RECURSIVE);
    }

    /**
     * Sets the ACTrustStore instance to use with the default
     * ACValidator. Default is <code>BasicVOMSTrustStore</code>
     *
     * @param trustStore
     * @see BasicVOMSTrustStore
     */
    public static void setTrustStore(ACTrustStore trustStore) {
        theTrustStore = trustStore;
    }

    /**
     * Convenience method: enables you to reuse a <code>VOMSValidator</code>
     * instance for another client chain, thus avoiding overhead in
     * instantiating validators and trust stores and other potentially
     * expensive operations.
     * <br>
     * This method returns the object itself, to allow for chaining
     * of commands: <br>
     * <code>vomsValidator.setValidatedChain(chain).validate().getVOMSAttributes();</code>
     *
     * @param validatedChain The new validated certificate chain to inspect
     * @return the object itself
     */
    public VOMSValidator setClientChain(X509Certificate[] validatedChain) {
        myValidatedChain = validatedChain;
        myVomsAttributes = new Vector();
        myFQANTree = null;
        isParsed = false;

        if (vp != null)
            vp = null;

        vp = new VomsdataPeer();
        isPreValidated = vp.Retrieve(validatedChain[0], validatedChain, VomsdataPeer.RECURSIVE);

        return this;
    }

    /**
     * Parses the assumed-validated certificate chain (which may also
     * include proxy certs) for any occurances of VOMS extensions containing
     * attribute certificates issued to the end entity in the certificate
     * chain.
     * <br>
     * <b>No validation of timestamps and/or signatures are
     * performed by this method.</b>
     * <br>
     * @return the voms attributes
     * @see #validate()
     */
    public static Vector parse(X509Certificate[] myValidatedChain) {
        System.out.println("WRONG");
        if (log.isDebugEnabled()) {
            log.debug("VOMSValidator : parsing cert chain");
        }

        int aclen = -1;

        int clientIdx = CertUtil.findClientCert(myValidatedChain);

        if (clientIdx < 0) {
            log.error("VOMSValidator : no client cert found in cert chain");
        }

        if (log.isDebugEnabled()) {
            log.debug("Parsing VOMS attributes for subject " +
                myValidatedChain[clientIdx].getSubjectX500Principal().getName());
        }

        VomsdataPeer vp = new VomsdataPeer("","");
        vp.Retrieve(myValidatedChain[0], myValidatedChain, VomsdataPeer.RECURSIVE);

        Vector myVomsAttributes = new Vector();

        for (int i = 0; i < myValidatedChain.length; i++) {
            byte[] payload = myValidatedChain[i].getExtensionValue(VOMS_EXT_OID);

            if (payload == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No VOMS extension in certificate issued to " +
                        myValidatedChain[i].getSubjectX500Principal().getName());
                }

                continue;
            }

            try {
                // Strip the wrapping OCTET STRING
                payload = ((DEROctetString) new DERInputStream(new ByteArrayInputStream(payload)).readObject()).getOctets();

                // VOMS extension is SEQUENCE of SET of AttributeCertificate
                // now, SET is an ordered sequence, and an AC is a sequence as
                // well -- thus the three nested ASN.1 sequences below...
                ASN1Sequence seq1 = (ASN1Sequence) new DERInputStream(new ByteArrayInputStream(payload)).readObject();

                for (Enumeration e1 = seq1.getObjects(); e1.hasMoreElements();) {
                    ASN1Sequence seq2 = (ASN1Sequence) e1.nextElement();


                    for (Enumeration e2 = seq2.getObjects(); e2.hasMoreElements();) {
                        AttributeCertificate ac = new AttributeCertificate((ASN1Sequence) e2.nextElement());
                        aclen++;

                        for (int j = clientIdx; j < myValidatedChain.length; j++) {
                            if (ac.getHolder().isHolder(myValidatedChain[j])) {
                                VOMSAttribute va = new VOMSAttribute(ac, vp.GetData(aclen));

                                if (log.isDebugEnabled()) {
                                    log.debug("Found VOMS attribute from " + va.getHostPort() +
                                        " in certificate issued to " +
                                        myValidatedChain[j].getSubjectX500Principal().getName());
                                }

                                myVomsAttributes.add(va);
                            }else{
                                log.debug("VOMS attribute cert found, but holder checking failed!");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.info("Error parsing VOMS extension in certificate issued to " +
                    myValidatedChain[i].getSubjectX500Principal().getName(), e);
                throw new IllegalArgumentException("Error parsing VOMS extension in certificate issued to " +
                        myValidatedChain[i].getSubjectX500Principal().getName() + "error was:" + e.getMessage());
            }
        }

        return myVomsAttributes;
    }
    
    /**
     * Parses the assumed-validated certificate chain (which may also
     * include proxy certs) for any occurances of VOMS extensions containing
     * attribute certificates issued to the end entity in the certificate
     * chain.
     * <br>
     * <b>No validation of timestamps and/or signatures are
     * performed by this method.</b>
     * <br>
     * This method returns the object itself, to allow for chaining
     * of commands: <br>
     * <code>new VOMSValidator(certChain).parse().getVOMSAttributes();</code>
     * @return the object itself
     * @see #validate()
     * @deprecated use the parse(X509Certificate[]) instead
     */
    public VOMSValidator parse() {
        System.out.println("CORRECT");
        if (log.isDebugEnabled()) {
            log.debug("VOMSValidator : parsing cert chain");
        }

        if (isParsed) {
            return this;
        }

        int aclen = -1;

        int clientIdx = CertUtil.findClientCert(myValidatedChain);

        if (clientIdx < 0) {
            log.error("VOMSValidator : no client cert found in cert chain");
        }

        if (log.isDebugEnabled()) {
            log.debug("Parsing VOMS attributes for subject " +
                myValidatedChain[clientIdx].getSubjectX500Principal().getName());
        }

        for (int i = 0; i < myValidatedChain.length; i++) {
            byte[] payload = myValidatedChain[i].getExtensionValue(VOMS_EXT_OID);

            if (payload == null) {
                if (log.isDebugEnabled()) {
                    log.debug("No VOMS extension in certificate issued to " +
                        myValidatedChain[i].getSubjectX500Principal().getName());
                }

                continue;
            }

            try {
                // Strip the wrapping OCTET STRING
                payload = ((DEROctetString) new DERInputStream(new ByteArrayInputStream(payload)).readObject()).getOctets();

                // VOMS extension is SEQUENCE of SET of AttributeCertificate
                // now, SET is an ordered sequence, and an AC is a sequence as
                // well -- thus the three nested ASN.1 sequences below...
                ASN1Sequence seq1 = (ASN1Sequence) new DERInputStream(new ByteArrayInputStream(payload)).readObject();

                for (Enumeration e1 = seq1.getObjects(); e1.hasMoreElements();) {
                    ASN1Sequence seq2 = (ASN1Sequence) e1.nextElement();

                    for (Enumeration e2 = seq2.getObjects(); e2.hasMoreElements();) {
                        AttributeCertificate ac = new AttributeCertificate((ASN1Sequence) e2.nextElement());

                        for (int j = clientIdx; j < myValidatedChain.length; j++) {
                            if (ac.getHolder().isHolder(myValidatedChain[j])) {
                                aclen++;
                                VOMSAttribute va = new VOMSAttribute(ac, vp.GetData(aclen));

                                if (log.isDebugEnabled()) {
                                    log.debug("Found VOMS attribute from " + va.getHostPort() +
                                        " in certificate issued to " +
                                        myValidatedChain[j].getSubjectX500Principal().getName());
                                }

                                myVomsAttributes.add(va);
                            }else{
                                log.debug("VOMS attribute cert found, but holder checking failed!");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.info("Error parsing VOMS extension in certificate issued to " +
                    myValidatedChain[i].getSubjectX500Principal().getName(), e);
            }
        }

        isParsed = true;

        return this;
    }
    
    /**
     * Parses the assumed-validated certificate chain (which may also
     * include proxy certs) for any occurances of VOMS extensions containing
     * attribute certificates issued to the end entity in the certificate
     * chain.
     * The attribute certificates are validated: any non-valid entries will
     * be ignored.
     * <br>
     * This method returns the object itself, to allow for chaining
     * of commands: <br>
     * <code>new VOMSValidator(certChain).parse().getVOMSAttributes();</code>
     * @return the object itself
     * @see #parse()
     */
    public VOMSValidator validate() {
        if (isValidated) {
            return this;
        }

        if (!isParsed) {
            parse();
            isParsed = true;
        }

        if (!isPreValidated) {
            for (ListIterator i = myVomsAttributes.listIterator(); i.hasNext();) {
                AttributeCertificate ac = ((VOMSAttribute) i.next()).getAC();

                if (!myValidator.validate(ac)) {
                    i.remove();
                }
            }
        }

        isValidated = true;

        return this;
    }

    /**
     * Populates the hierarchial FQAN tree with the parsed and/or
     * validated ACs.
     */
    private void populate() {
        if (!isParsed && !isValidated) {
            throw new IllegalStateException(
                "VOMSValidator: trying to populate FQAN tree before call to parse() or validate()");
        }

        myFQANTree = new FQANTree();

        for (ListIterator i = myVomsAttributes.listIterator(); i.hasNext();) {
            myFQANTree.add(((VOMSAttribute) i.next()).getListOfFQAN());
        }
    }

    /**
     * Returns a list of VOMS attributes, parsed and possibly validated.
     * @return List of <code>VOMSAttribute</code>
     * @see org.glite.security.voms.VOMSAttribute
     * @see #parse()
     * @see #validate()
     * @see #isValidated()
     */
    public List getVOMSAttributes() {
        return myVomsAttributes;
    }

    /**
     * Returns a list of all roles attributed to a (sub)group, by
     * combining all VOMS attributes in a hiearchial fashion.
     * <br>
     * <b>Note:</b> One of the methods <code>parse()</code> or
     * <code>validate()</code> must have been called before calling
     * this method. Otherwise, an <code>IllegalStateException</code>
     * is thrown.
     *
     * @param subGroup
     * @see #FQANTree
     * @return
     */
    public List getRoles(String subGroup) {
        if (!isParsed && !isValidated) {
            throw new IllegalStateException("Must call parse() or validate() first");
        }

        if (myFQANTree == null) {
            populate();
        }

        return myFQANTree.getRoles(subGroup);
    }

    /**
     * Returns a list of all capabilities attributed to a (sub)group,
     * by combining all VOMS attributes in a hiearchial fashion.
     * <br>
     * <b>Note:</b> One of the methods <code>parse()</code> or
     * <code>validate()</code> must have been called before calling
     * this method. Otherwise, an <code>IllegalStateException</code>
     * is thrown.
     *
     * @param subGroup
     * @see #FQANTree
     * @return
     */
    public List getCapabilities(String subGroup) {
        if (!isParsed && !isValidated) {
            throw new IllegalStateException("Must call parse() or validate() first");
        }

        if (myFQANTree == null) {
            populate();
        }

        return myFQANTree.getCapabilities(subGroup);
    }

    /**
     * @return whether the VOMS attributes are validated or not
     *
     * @see #validate()
     */
    public boolean isValidated() {
        return isValidated;
    }

    public String toString() {
        return "isParsed : " + isParsed + "\nisValidated : " + isValidated + "\nVOMS attrs:" + myVomsAttributes;
    }

    /**
     * Helper container that fills up with roles and capabilties
     * as the FQANTree is traversed.
     */
    class RoleCaps {
        // NOTE: these are not initialized by default, but only if this
        // structure is added non-null Vector content via add(). That
        // way, we can distuingish between the returning null and the empty
        // set (as the Vector may be empty, consider FQAN "/A/Role=")
        List roles;
        List caps;

        void add(List v, String s) {
            if (s == null) {
                return;
            }

            if (!v.contains(s)) {
                v.add(s);
            }
        }

        public void add(Vector fqans) {
            if (fqans == null) {
                return;
            }

            if (roles == null) {
                roles = new Vector();
                caps = new Vector();
            }

            for (Iterator i = fqans.iterator(); i.hasNext();) {
                FQAN f = (FQAN) i.next();
                add(roles, f.getRole());
                add(caps, f.getCapability());
            }
        }

        public List getRoles() {
            return roles;
        }

        public List getCapabilities() {
            return caps;
        }
    }

    /**
     * Class to sort out the hierarchial properties of FQANs. For example,
     * given the FQANs <code>/VO/Role=admin</code> and
     * </code>/VO/SubGroup/Role=user</code>, this means that the
     * applicable roles for </code>/VO/SubGroup</vo> is both
     * <code>admin</code> as well as <code>user</code>
     */
    public class FQANTree {
        Hashtable myTree = new Hashtable();
        Hashtable myResults = new Hashtable();

        public void add(List fqans) {
            if (fqans == null) {
                return;
            }

            for (Iterator i = fqans.iterator(); i.hasNext();) {
                add((FQAN) i.next());
            }
        }

        public void add(FQAN fqan) {
            String group = fqan.getGroup();
            Vector v = (Vector) myTree.get(group);

            if (v == null) {
                myTree.put(group, v = new Vector());
            }

            if (!v.contains(fqan)) {
                v.add(fqan);
            }
        }

        protected RoleCaps traverse(String voGroup) {
            RoleCaps rc = (RoleCaps) myResults.get(voGroup);

            if (rc != null) {
                return rc;
            }

            rc = new RoleCaps();

            StringTokenizer tok = new StringTokenizer(voGroup, "/", true);
            StringBuffer sb = new StringBuffer();

            while (tok.hasMoreTokens()) {
                sb.append(tok.nextToken());
                rc.add((Vector) myTree.get(sb.toString()));
            }

            myResults.put(voGroup, rc);

            return rc;
        }

        public List getRoles(String voGroup) {
            return traverse(voGroup).getRoles();
        }

        public List getCapabilities(String voGroup) {
            return traverse(voGroup).getCapabilities();
        }
    }
}