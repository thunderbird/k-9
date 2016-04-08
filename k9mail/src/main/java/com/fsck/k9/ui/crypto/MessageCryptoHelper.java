package com.fsck.k9.ui.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.os.AsyncTask;
import android.util.Log;

import com.fsck.k9.Account;
import com.fsck.k9.K9;
import com.fsck.k9.R;
import com.fsck.k9.crypto.CryptoPartFinder;
import com.fsck.k9.mail.Body;
import com.fsck.k9.mail.BodyPart;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.Multipart;
import com.fsck.k9.mail.Part;
import com.fsck.k9.mail.internet.MessageExtractor;
import com.fsck.k9.mail.internet.MimeBodyPart;
import com.fsck.k9.mail.internet.TextBody;
import com.fsck.k9.mailstore.CryptoErrorType;
import com.fsck.k9.mailstore.CryptoResultAnnotation;
import com.fsck.k9.mailstore.CryptoSignatureResult;
import com.fsck.k9.mailstore.DecryptStreamParser;
import com.fsck.k9.mailstore.LocalMessage;
import com.fsck.k9.mailstore.MessageHelper;
import com.fsck.k9.mailstore.CryptoError;

import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpDecryptionResult;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.OpenPgpSignatureResult;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpApi.IOpenPgpCallback;
import org.openintents.openpgp.util.OpenPgpServiceConnection;
import org.openintents.smime.ISMimeService;
import org.openintents.smime.SmimeDecryptionResult;
import org.openintents.smime.SmimeError;
import org.openintents.smime.SmimeSignatureResult;
import org.openintents.smime.util.SMimeApi;
import org.openintents.smime.util.SMimeServiceConnection;


public class MessageCryptoHelper {
    private static final int REQUEST_CODE_OPENPGP = 1000;
    private static final int REQUEST_CODE_SMIME = 1100;
    private static final int INVALID_OPENPGP_RESULT_CODE = -1;
    private static final int INVALID_SMIME_RESULT_CODE = -1;
    private static final MimeBodyPart NO_REPLACEMENT_PART = null;


    private final Context context;
    private final Activity activity;
    private final MessageCryptoCallback callback;
    private final Account account;
    private LocalMessage message;

    private Deque<CryptoPart> partsToDecryptOrVerify = new ArrayDeque<CryptoPart>();
    private OpenPgpApi openPgpApi;
    private SMimeApi smimeApi;
    private CryptoPart currentCryptoPart;
    private Intent currentCryptoResult;

    private MessageCryptoAnnotations messageAnnotations;
    private Intent userInteractionResultIntent;


    public MessageCryptoHelper(Activity activity, Account account, MessageCryptoCallback callback) {
        this.context = activity.getApplicationContext();
        this.activity = activity;
        this.callback = callback;
        this.account = account;

        this.messageAnnotations = new MessageCryptoAnnotations();
    }

    public void decryptOrVerifyMessagePartsIfNecessary(LocalMessage message) {
        this.message = message;

        if (!account.isOpenPgpProviderConfigured() && !account.isSmimeProviderConfigured()) {
            returnResultToFragment();
            return;
        }

        if (account.isOpenPgpProviderConfigured()) {

            List<Part> pgpEncryptedParts = CryptoPartFinder.findPgpEncryptedParts(message);
            processFoundParts(pgpEncryptedParts, CryptoPartType.ENCRYPTED, CryptoMethod.OPENPGP,
                    CryptoErrorType.ENCRYPTED_BUT_INCOMPLETE,
                    MessageHelper.createEmptyPart());

            List<Part> pgpSignedParts = CryptoPartFinder.findPgpSignedParts(message);
            processFoundParts(pgpSignedParts, CryptoPartType.SIGNED, CryptoMethod.OPENPGP,
                    CryptoErrorType.SIGNED_BUT_INCOMPLETE, NO_REPLACEMENT_PART);

            List<Part> inlineParts = CryptoPartFinder.findPgpInlineParts(message);
            addFoundInlinePgpParts(inlineParts);
        }

        if(account.isSmimeProviderConfigured()) {

            List<Part> smimeEncryptedParts = CryptoPartFinder.findSmimeEncryptedParts(message);
            processFoundParts(smimeEncryptedParts, CryptoPartType.ENCRYPTED, CryptoMethod.SMIME,
                    CryptoErrorType.ENCRYPTED_BUT_INCOMPLETE,
                    MessageHelper.createEmptyPart());

            List<Part> smimeSignedParts = CryptoPartFinder.findSmimeSignedParts(message);
            processFoundParts(smimeSignedParts, CryptoPartType.SIGNED, CryptoMethod.SMIME,
                    CryptoErrorType.SIGNED_BUT_INCOMPLETE, NO_REPLACEMENT_PART);
        }

        decryptOrVerifyNextPart();
    }

    public void handleCryptoResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_OPENPGP) {
            handleOpenPgpResult(requestCode, resultCode, data);
        } else if(requestCode == REQUEST_CODE_SMIME) {
            handleSmimeResult(requestCode, resultCode, data);
        }
    }

    private void processFoundParts(List<Part> foundParts,
                                   CryptoPartType cryptoPartType,
                                   CryptoMethod cryptoMethod,
                                   CryptoErrorType errorIfIncomplete,
            MimeBodyPart replacementPart) {
        for (Part part : foundParts) {
            if (MessageHelper.isCompletePartAvailable(part)) {
                CryptoPart cryptoPart = new CryptoPart(cryptoPartType, cryptoMethod, part);
                partsToDecryptOrVerify.add(cryptoPart);
            } else {
                Log.w(K9.LOG_TAG, "Found part was incomplete");
                addCryptoErrorAnnotation(part, errorIfIncomplete, replacementPart);

            }
        }
    }

    private void addCryptoErrorAnnotation(Part part, CryptoErrorType error, MimeBodyPart outputData) {
        CryptoResultAnnotation annotation = new CryptoResultAnnotation();
        annotation.setErrorType(error);
        annotation.setOutputData(outputData);
        messageAnnotations.put(part, annotation);
    }

    private void addFoundInlinePgpParts(List<Part> foundParts) {
        for (Part part : foundParts) {
            CryptoPart cryptoPart = new CryptoPart(CryptoPartType.INLINE_PGP, CryptoMethod.OPENPGP, part);
            partsToDecryptOrVerify.add(cryptoPart);
        }
    }

    private void decryptOrVerifyNextPart() {
        if (partsToDecryptOrVerify.isEmpty()) {
            returnResultToFragment();
            return;
        }

        CryptoPart cryptoPart = partsToDecryptOrVerify.peekFirst();
        startDecryptingOrVerifyingPart(cryptoPart);
    }

    private void startDecryptingOrVerifyingPart(CryptoPart cryptoPart) {
        if (cryptoPart.method == CryptoMethod.OPENPGP) {
            if (!isBoundToOpenPgpProviderService()) {
                connectToOpenPgpProviderService();
            } else {
                decryptOrVerifyPart(cryptoPart);
            }
        } else if (cryptoPart.method == CryptoMethod.SMIME) {
            if (!isBoundToSmimeProviderService()) {
                connectToSmimeProviderService();
            } else {
                decryptOrVerifyPart(cryptoPart);
            }
        } else {
            throw new AssertionError("All crypto methods should be handled");
        }
    }

    private boolean isBoundToOpenPgpProviderService() {
        return openPgpApi != null;
    }

    private boolean isBoundToSmimeProviderService() {
        return smimeApi != null;
    }

    private void connectToOpenPgpProviderService() {
        String openPgpProvider = account.getOpenPgpProvider();
        new OpenPgpServiceConnection(context, openPgpProvider,
                new org.openintents.openpgp.util.OpenPgpServiceConnection.OnBound() {
                    @Override
                    public void onBound(IOpenPgpService2 service) {
                        openPgpApi = new OpenPgpApi(context, service);

                        decryptOrVerifyNextPart();
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(K9.LOG_TAG, "Couldn't connect to OpenPgpService", e);
                    }
                }).bindToService();
    }

    private void connectToSmimeProviderService() {
        String smimeProvider = account.getSmimeProvider();
        new SMimeServiceConnection(context, smimeProvider,
                new org.openintents.smime.util.SMimeServiceConnection.OnBound() {
                    @Override
                    public void onBound(ISMimeService service) {
                        smimeApi = new SMimeApi(context, service);

                        decryptOrVerifyNextPart();
                    }

                    @Override
                    public void onError(Exception e) {
                        Log.e(K9.LOG_TAG, "Couldn't connect to SmimeService", e);
                    }
                }).bindToService();
    }

    private void decryptOrVerifyPart(CryptoPart cryptoPart) {
        currentCryptoPart = cryptoPart;
        Intent decryptIntent = userInteractionResultIntent;
        userInteractionResultIntent = null;
        if (decryptIntent == null) {
            decryptIntent = new Intent();
        }
        if (cryptoPart.method == CryptoMethod.OPENPGP)
            openPgpDecryptVerify(decryptIntent);
        else if (cryptoPart.method == CryptoMethod.SMIME)
            smimeDecryptVerify(decryptIntent);
    }

    private void openPgpDecryptVerify(Intent intent) {
        intent.setAction(OpenPgpApi.ACTION_DECRYPT_VERIFY);

        try {
            CryptoPartType cryptoPartType = currentCryptoPart.type;
            switch (cryptoPartType) {
                case SIGNED: {
                    callAsyncDetachedVerify(intent);
                    return;
                }
                case ENCRYPTED: {
                    callAsyncDecrypt(intent);
                    return;
                }
                case INLINE_PGP: {
                    callAsyncInlineOperation(intent);
                    return;
                }
            }

            throw new IllegalStateException("Unknown crypto part type: " + cryptoPartType);
        } catch (IOException e) {
            Log.e(K9.LOG_TAG, "IOException", e);
        } catch (MessagingException e) {
            Log.e(K9.LOG_TAG, "MessagingException", e);
        }
    }

    private void smimeDecryptVerify(Intent intent) {
        intent.setAction(SMimeApi.ACTION_DECRYPT_VERIFY);
        try {
            CryptoPartType cryptoPartType = currentCryptoPart.type;
            switch (cryptoPartType) {
                case SIGNED: {
                    callAsyncDetachedVerify(intent);
                    return;
                }
                case ENCRYPTED: {
                    callAsyncDecrypt(intent);
                    return;
                }
            }

            throw new IllegalStateException("Unknown crypto part type: " + cryptoPartType);
        } catch (IOException e) {
            Log.e(K9.LOG_TAG, "IOException", e);
        } catch (MessagingException e) {
            Log.e(K9.LOG_TAG, "MessagingException", e);
        }
    }

    private void callAsyncInlineOperation(Intent intent) throws IOException {
        PipedInputStream pipedInputStream = getPipedInputStreamForEncryptedOrInlineData();
        final ByteArrayOutputStream decryptedOutputStream = new ByteArrayOutputStream();
        switch (currentCryptoPart.method) {
            case OPENPGP:
                openPgpApi.executeApiAsync(intent, pipedInputStream, decryptedOutputStream, new IOpenPgpCallback() {
                    @Override
                    public void onReturn(Intent result) {
                        currentCryptoResult = result;

                        MimeBodyPart decryptedPart = null;
                        try {
                            TextBody body = new TextBody(new String(decryptedOutputStream.toByteArray()));
                            decryptedPart = new MimeBodyPart(body, "text/plain");
                        } catch (MessagingException e) {
                            Log.e(K9.LOG_TAG, "MessagingException", e);
                        }

                        onOpenPgpOperationReturned(decryptedPart);
                    }
                });
                break;
            case SMIME:
                throw new UnsupportedOperationException("S/MIME doesn't support inline");
        }
    }

    private void callAsyncDecrypt(Intent intent) throws IOException {
        final CountDownLatch latch = new CountDownLatch(1);
        PipedInputStream pipedInputStream = getPipedInputStreamForEncryptedOrInlineData();
        PipedOutputStream decryptedOutputStream = getPipedOutputStreamForDecryptedData(latch);
        switch (currentCryptoPart.method) {
            case OPENPGP:
                openPgpApi.executeApiAsync(intent, pipedInputStream, decryptedOutputStream,
                new OpenPgpApi.IOpenPgpCallback() {
                    @Override
                    public void onReturn(Intent result) {
                        currentCryptoResult = result;
                        latch.countDown();
                    }
                });
                break;
            case SMIME:
                smimeApi.executeApiAsync(intent, pipedInputStream, decryptedOutputStream,
                new SMimeApi.ISMimeCallback() {
                    @Override
                    public void onReturn(Intent result) {
                        currentCryptoResult = result;
                        latch.countDown();
                    }
                });
                break;
        }
    }

    private void callAsyncDetachedVerify(Intent intent) throws IOException, MessagingException {
        PipedInputStream pipedInputStream = getPipedInputStreamForSignedData();

        byte[] signatureData = CryptoPartFinder.getSignatureData(currentCryptoPart.part);
        intent.putExtra(OpenPgpApi.EXTRA_DETACHED_SIGNATURE, signatureData);
        switch (currentCryptoPart.method) {
            case OPENPGP:
                openPgpApi.executeApiAsync(intent, pipedInputStream, null, new OpenPgpApi.IOpenPgpCallback() {
                    @Override
                    public void onReturn(Intent result) {
                        currentCryptoResult = result;
                        onOpenPgpOperationReturned(null);
                    }
                });
                break;
            case SMIME:
                smimeApi.executeApiAsync(intent, pipedInputStream, null, new SMimeApi.ISMimeCallback() {
                    @Override
                    public void onReturn(Intent result) {
                        currentCryptoResult = result;
                        onSmimeOperationReturned(null);
                    }
                });
                break;
        }
    }

    private PipedInputStream getPipedInputStreamForSignedData() throws IOException {
        PipedInputStream pipedInputStream = new PipedInputStream();

        final PipedOutputStream out = new PipedOutputStream(pipedInputStream);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Multipart multipartSignedMultipart = (Multipart) currentCryptoPart.part.getBody();
                    BodyPart signatureBodyPart = multipartSignedMultipart.getBodyPart(0);
                    Log.d(K9.LOG_TAG, "signed data type: " + signatureBodyPart.getMimeType());
                    signatureBodyPart.writeTo(out);
                } catch (Exception e) {
                    Log.e(K9.LOG_TAG, "Exception while writing message to crypto provider", e);
                } finally {
                    try {
                        out.close();
                    } catch (IOException e) {
                        // don't care
                    }
                }
            }
        }).start();

        return pipedInputStream;
    }

    private PipedInputStream getPipedInputStreamForEncryptedOrInlineData() throws IOException {
        PipedInputStream pipedInputStream = new PipedInputStream();

        final PipedOutputStream out = new PipedOutputStream(pipedInputStream);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Part part = currentCryptoPart.part;
                    CryptoPartType cryptoPartType = currentCryptoPart.type;
                    if (cryptoPartType == CryptoPartType.ENCRYPTED) {
                        Multipart multipartEncryptedMultipart = (Multipart) part.getBody();
                        BodyPart encryptionPayloadPart = multipartEncryptedMultipart.getBodyPart(1);
                        Body encryptionPayloadBody = encryptionPayloadPart.getBody();
                        encryptionPayloadBody.writeTo(out);
                    } else if (cryptoPartType == CryptoPartType.INLINE_PGP) {
                        String text = MessageExtractor.getTextFromPart(part);
                        out.write(text.getBytes());
                    } else {
                        Log.wtf(K9.LOG_TAG, "No suitable data to stream found!");
                    }
                } catch (Exception e) {
                    Log.e(K9.LOG_TAG, "Exception while writing message to crypto provider", e);
                } finally {
                    try {
                        out.close();
                    } catch (IOException e) {
                        // don't care
                    }
                }
            }
        }).start();

        return pipedInputStream;
    }

    private PipedOutputStream getPipedOutputStreamForDecryptedData(final CountDownLatch latch) throws IOException {
        PipedOutputStream decryptedOutputStream = new PipedOutputStream();
        final PipedInputStream decryptedInputStream = new PipedInputStream(decryptedOutputStream);
        new AsyncTask<Void, Void, MimeBodyPart>() {
            @Override
            protected MimeBodyPart doInBackground(Void... params) {
                MimeBodyPart decryptedPart = null;
                try {
                    decryptedPart = DecryptStreamParser.parse(context, decryptedInputStream);

                    latch.await();
                } catch (InterruptedException e) {
                    Log.w(K9.LOG_TAG, "we were interrupted while waiting for onReturn!", e);
                } catch (Exception e) {
                    Log.e(K9.LOG_TAG, "Something went wrong while parsing the decrypted MIME part", e);
                    //TODO: pass error to main thread and display error message to user
                }
                return decryptedPart;
            }

            @Override
            protected void onPostExecute(MimeBodyPart decryptedPart) {
                onOpenPgpOperationReturned(decryptedPart);
            }
        }.execute();
        return decryptedOutputStream;
    }

    private void onOpenPgpOperationReturned(MimeBodyPart outputPart) {

        if (currentCryptoResult == null) {
            Log.e(K9.LOG_TAG, "Internal error: we should have a result here!");
            return;
        }

        try {
            handleOpenPgpOperationResult(outputPart);
        } finally {
            currentCryptoResult = null;
        }
    }

    private void handleOpenPgpOperationResult(MimeBodyPart outputPart) {
        int resultCode = currentCryptoResult.getIntExtra(OpenPgpApi.RESULT_CODE, INVALID_OPENPGP_RESULT_CODE);
        if (K9.DEBUG) {
            Log.d(K9.LOG_TAG, "OpenPGP API decryptVerify result code: " + resultCode);
        }

        switch (resultCode) {
            case INVALID_OPENPGP_RESULT_CODE: {
                Log.e(K9.LOG_TAG, "Internal error: no result code!");
                break;
            }
            case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED: {
                handleOpenPgpUserInteractionRequest();
                break;
            }
            case OpenPgpApi.RESULT_CODE_ERROR: {
                handleOpenPgpOperationError();
                break;
            }
            case OpenPgpApi.RESULT_CODE_SUCCESS: {
                handleOpenPgpOperationSuccess(outputPart);
                break;
            }
        }
    }

    private void handleOpenPgpUserInteractionRequest() {
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
        if (pendingIntent == null) {
            throw new AssertionError("Expecting PendingIntent on USER_INTERACTION_REQUIRED!");
        }

        try {
            activity.startIntentSenderForResult(pendingIntent.getIntentSender(), REQUEST_CODE_OPENPGP, null, 0, 0, 0);
        } catch (SendIntentException e) {
            Log.e(K9.LOG_TAG, "Internal error on starting pendingintent!", e);
        }
    }

    private void handleOpenPgpOperationError() {
        OpenPgpError error = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
        if (K9.DEBUG) {
            Log.w(K9.LOG_TAG, "OpenPGP API error: " + error.getMessage());
        }

        onCryptoFailed(new CryptoError(error));
    }

    private void handleOpenPgpOperationSuccess(MimeBodyPart outputPart) {
        OpenPgpDecryptionResult decryptionResult =
                currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_DECRYPTION);
        OpenPgpSignatureResult signatureResult =
                currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_SIGNATURE);
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(OpenPgpApi.RESULT_INTENT);

        CryptoResultAnnotation resultAnnotation = new CryptoResultAnnotation();
        resultAnnotation.setOutputData(outputPart);
        resultAnnotation.setDecryptionResult(new CryptoDecryptionResult(decryptionResult));
        resultAnnotation.setSignatureResult(new CryptoSignatureResult(signatureResult));
        resultAnnotation.setPendingIntent(pendingIntent);

        onCryptoSuccess(resultAnnotation);
    }

    public void handleOpenPgpResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_OPENPGP) {
            return;
        }

        if (resultCode == Activity.RESULT_OK) {
            userInteractionResultIntent = data;
            decryptOrVerifyNextPart();
        } else {
            onCryptoFailed(new CryptoError(new OpenPgpError(OpenPgpError.CLIENT_SIDE_ERROR, context.getString(R.string.openpgp_canceled_by_user))));
        }
    }

    private void onCryptoSuccess(CryptoResultAnnotation resultAnnotation) {
        addCryptoResultPartToMessage(resultAnnotation);
        onCryptoFinished();
    }

    private void addCryptoResultPartToMessage(CryptoResultAnnotation resultAnnotation) {
        Part part = currentCryptoPart.part;
        messageAnnotations.put(part, resultAnnotation);
    }

    private void onCryptoFailed(CryptoError error) {
        CryptoResultAnnotation errorPart = new CryptoResultAnnotation();
        errorPart.setError(error);
        addCryptoResultPartToMessage(errorPart);
        onCryptoFinished();
    }

    private void onSmimeOperationReturned(MimeBodyPart outputPart) {
        if (currentCryptoResult == null) {
            Log.e(K9.LOG_TAG, "Internal error: we should have a result here!");
            return;
        }

        try {
            handleSmimeOperationResult(outputPart);
        } finally {
            currentCryptoResult = null;
        }
    }

    private void handleSmimeOperationResult(MimeBodyPart outputPart) {
        int resultCode = currentCryptoResult.getIntExtra(SMimeApi.RESULT_CODE, INVALID_SMIME_RESULT_CODE);
        if (K9.DEBUG) {
            Log.d(K9.LOG_TAG, "S/MIME API decryptVerify result code: " + resultCode);
        }

        switch (resultCode) {
            case INVALID_SMIME_RESULT_CODE: {
                Log.e(K9.LOG_TAG, "Internal error: no result code!");
                break;
            }
            case SMimeApi.RESULT_CODE_USER_INTERACTION_REQUIRED: {
                handleSmimeUserInteractionRequest();
                break;
            }
            case SMimeApi.RESULT_CODE_ERROR: {
                handleSmimeOperationError();
                break;
            }
            case SMimeApi.RESULT_CODE_SUCCESS: {
                handleSmimeOperationSuccess(outputPart);
                break;
            }
        }
    }

    private void handleSmimeUserInteractionRequest() {
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_INTENT);
        if (pendingIntent == null) {
            throw new AssertionError("Expecting PendingIntent on USER_INTERACTION_REQUIRED!");
        }

        try {
            activity.startIntentSenderForResult(pendingIntent.getIntentSender(), REQUEST_CODE_SMIME, null, 0, 0, 0);
        } catch (SendIntentException e) {
            Log.e(K9.LOG_TAG, "Internal error on starting pendingintent!", e);
        }
    }

    private void handleSmimeOperationError() {
        SmimeError error = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_ERROR);
        if (K9.DEBUG) {
            Log.w(K9.LOG_TAG, "S/MIME API error: " + error.getMessage());
        }

        onCryptoFailed(new CryptoError(error));
    }

    private void handleSmimeOperationSuccess(MimeBodyPart outputPart) {
        SmimeDecryptionResult decryptionResult =
                currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_DECRYPTION);
        SmimeSignatureResult signatureResult =
                currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_SIGNATURE);
        PendingIntent pendingIntent = currentCryptoResult.getParcelableExtra(SMimeApi.RESULT_INTENT);

        CryptoResultAnnotation resultAnnotation = new CryptoResultAnnotation();
        resultAnnotation.setOutputData(outputPart);
        resultAnnotation.setDecryptionResult(new CryptoDecryptionResult(decryptionResult));
        resultAnnotation.setSignatureResult(new CryptoSignatureResult(signatureResult));
        resultAnnotation.setPendingIntent(pendingIntent);

        onCryptoSuccess(resultAnnotation);
    }

    public void handleSmimeResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_OPENPGP) {
            return;
        }

        if (resultCode == Activity.RESULT_OK) {
            userInteractionResultIntent = data;
            decryptOrVerifyNextPart();
        } else {
            onCryptoFailed(new CryptoError(new SmimeError(SmimeError.CLIENT_SIDE_ERROR,
                    context.getString(R.string.smime_canceled_by_user))));
        }
    }

    private void onCryptoFinished() {
        partsToDecryptOrVerify.removeFirst();
        decryptOrVerifyNextPart();
    }

    private void returnResultToFragment() {
        callback.onCryptoOperationsFinished(messageAnnotations);
    }

    private static class CryptoPart {
        public final CryptoPartType type;
        public final CryptoMethod method;
        public final Part part;

        CryptoPart(CryptoPartType type, CryptoMethod method, Part part) {
            this.type = type;
            this.method = method;
            this.part = part;
        }
    }

    private enum CryptoPartType {
        INLINE_PGP,
        ENCRYPTED,
        SIGNED
    }
}
