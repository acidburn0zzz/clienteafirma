/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   - the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   - or The European Software License; either version 1.1 or (at your option) any later version.
 * Date: 11/01/11
 * You may contact the copyright holder at: soporte.afirma5@mpt.es
 */

package es.gob.afirma.signers.pades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.Logger;

import com.lowagie.text.DocumentException;
import com.lowagie.text.Image;
import com.lowagie.text.Jpeg;
import com.lowagie.text.Rectangle;
import com.lowagie.text.exceptions.BadPasswordException;
import com.lowagie.text.pdf.PdfDate;
import com.lowagie.text.pdf.PdfDeveloperExtension;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfPKCS7;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfSignature;
import com.lowagie.text.pdf.PdfSignatureAppearance;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.PdfWriter;

import es.gob.afirma.core.AOCancelledOperationException;
import es.gob.afirma.core.AOException;
import es.gob.afirma.core.misc.Base64;
import es.gob.afirma.core.misc.Platform;
import es.gob.afirma.core.misc.Platform.OS;
import es.gob.afirma.core.ui.AOUIFactory;

/** Gestor del n&uacute;cleo de firma PDF. Esta clase realiza las operaciones necesarias tanto para
 * la firma monof&aacute;sica PAdES como para las trif&aacute;sicas de una forma unificada, pero
 * &uacute;nicamente en lo referente al formato PDF, sin entrar en la parte CAdES o PKCS#7
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s */
public final class PdfSessionManager {

    /** Referencia a la &uacute;ltima p&aacute;gina del documento PDF. */
    public static final int LAST_PAGE = -666;

    private static final int UNDEFINED = -1;
    private static final int DEFAULT_LAYER_2_FONT_SIZE = 12;
    private static final int COURIER = 0;

    private static final int CSIZE = 27000;

    private static final Logger LOGGER = Logger.getLogger("es.gob.afirma");  //$NON-NLS-1$

    /** Obtiene los datos PDF relevantes en cuanto a las firmas electr&oacute;nicas, consistentes en los datos
     * a ser firmados con CAdES o PKCS#7 y los metadatos necesarios para su correcta inserci&oacute;n en el PDF.
     * @param inPDF Documento PDF que se desea firmar
     * @param certChain Cadena de certificados del firmante
     * @param signTime Hora de la firma
     * @param extraParams Par&aacute;metros adicionales de la firma
     * @return Datos PDF relevantes en cuanto a las firmas electr&oacute;nicas
     * @throws AOException
     * @throws IOException
     * @throws DocumentException */
    public static PdfTriPhaseSession getSessionData(final byte[] inPDF,
                                                     final X509Certificate[] certChain,
                                                     final Calendar signTime,
                                                     final Properties extraParams) throws AOException,
                                                                                          IOException,
                                                                                          DocumentException {

		// *********************************************************************************************************************
		// **************** LECTURA PARAMETROS ADICIONALES *********************************************************************
		// *********************************************************************************************************************

		// Imagen de la rubrica
		final Image rubric = getRubricImage(extraParams.getProperty("signatureRubricImage")); //$NON-NLS-1$

		// Motivo de la firma
		final String reason = extraParams.getProperty("signReason"); //$NON-NLS-1$

		// Nombre del campo de firma preexistente en el PDF a usar
		final String signatureField = extraParams.getProperty("signatureField"); //$NON-NLS-1$

		// Lugar de realizacion de la firma
		final String signatureProductionCity = extraParams.getProperty("signatureProductionCity"); //$NON-NLS-1$

		// Datos de contacto (correo electronico) del firmante
		final String signerContact = extraParams.getProperty("signerContact"); //$NON-NLS-1$

		// Pagina donde situar la firma visible
		int page = LAST_PAGE;
		try {
			page = Integer.parseInt(extraParams.getProperty("signaturePage")); //$NON-NLS-1$
		}
		catch (final Exception e) {
			/* Se deja la pagina tal y como esta */
		}

		// Nombre del subfiltro de firma en el diccionario PDF
		final String signatureSubFilter = extraParams.getProperty("signatureSubFilter"); //$NON-NLS-1$

		// ******************
		// ** Adjuntos ******

		// Contenido a adjuntar (en Base64)
		final String b64Attachment = extraParams.getProperty("attach"); //$NON-NLS-1$

		// Nombre que se pondra al fichero adjunto en el PDF
		final String attachmentFileName = extraParams.getProperty("attachFileName"); //$NON-NLS-1$

		// Descripcion del adjunto
		final String attachmentDescription = extraParams.getProperty("attachDescription"); //$NON-NLS-1$

		// ** Fin Adjuntos **
		// ******************

		// Nivel de certificacion del PDF
		int certificationLevel;
		try {
			certificationLevel = extraParams.getProperty("certificationLevel") != null ? //$NON-NLS-1$
				Integer.parseInt(extraParams.getProperty("certificationLevel")) : //$NON-NLS-1$
					-1;
		}
		catch(final Exception e) {
			certificationLevel = UNDEFINED;
		}

		// *****************************
		// **** Texto firma visible ****

		// Texto en capa 4
		final String layer4Text = extraParams.getProperty("layer4Text"); //$NON-NLS-1$

		// Texto en capa 2
		final String layer2Text = extraParams.getProperty("layer2Text"); //$NON-NLS-1$

		// Tipo de letra en capa 2
		int layer2FontFamily;
		try {
			layer2FontFamily = extraParams.getProperty("layer2FontFamily") != null ? //$NON-NLS-1$
				Integer.parseInt(extraParams.getProperty("layer2FontFamily")) : //$NON-NLS-1$
					-1;
		}
		catch(final Exception e) {
			layer2FontFamily = UNDEFINED;
		}

		// Tamano del tipo de letra en capa 2
		int layer2FontSize;
		try {
			layer2FontSize = extraParams.getProperty("layer2FontSize") != null ? //$NON-NLS-1$
				Integer.parseInt(extraParams.getProperty("layer2FontSize")) : //$NON-NLS-1$
					-1;
		}
		catch(final Exception e) {
			layer2FontSize = UNDEFINED;
		}

		// Estilo del tipo de letra en capa 2
		int layer2FontStyle;
		try {
			layer2FontStyle = extraParams.getProperty("layer2FontStyle") != null ? //$NON-NLS-1$
				Integer.parseInt(extraParams.getProperty("layer2FontStyle")) : //$NON-NLS-1$
					-1;
		}
		catch(final Exception e) {
			layer2FontStyle = UNDEFINED;
		}

		// Color del tipo de letra en capa 2
		final String layer2FontColor = extraParams.getProperty("layer2FontColor"); //$NON-NLS-1$

		// ** Fin texto firma visible **
		// *****************************

		// Contrasena del propietario del PDF
		String ownerPassword = extraParams.getProperty("ownerPassword"); //$NON-NLS-1$

		// Contrasena del usuario del PDF
		final String userPassword =  extraParams.getProperty("userPassword"); //$NON-NLS-1$

		// *********************************************************************************************************************
		// **************** FIN LECTURA PARAMETROS ADICIONALES *****************************************************************
		// *********************************************************************************************************************

		PdfReader pdfReader;
		try {
			if (ownerPassword != null) {
				pdfReader = new PdfReader(inPDF, ownerPassword.getBytes());
			}
			else if (userPassword != null) {
				pdfReader = new PdfReader(inPDF, userPassword.getBytes());
			}
			else {
				pdfReader = new PdfReader(inPDF);
			}
		}
		catch (final BadPasswordException e) {
			// Comprobamos que el signer esta en modo interactivo, y si no lo
			// esta no pedimos contrasena por dialogo, principalmente para no interrumpir un firmado por lotes
			// desatendido
			if (Boolean.TRUE.toString().equalsIgnoreCase(extraParams.getProperty("headLess"))) { //$NON-NLS-1$
				throw new BadPdfPasswordException(e);
			}
			// La contrasena que nos han proporcionada no es buena o no nos
			// proporcionaron ninguna
			ownerPassword = new String(
				AOUIFactory.getPassword(
					ownerPassword == null ? CommonPdfMessages.getString("AOPDFSigner.0") : CommonPdfMessages.getString("AOPDFSigner.1"), //$NON-NLS-1$ //$NON-NLS-2$
					null
				)
			);
			try {
				pdfReader = new PdfReader(inPDF, ownerPassword.getBytes());
			}
			catch (final BadPasswordException e2) {
				throw new BadPdfPasswordException(e2);
			}
		}
		catch (final IOException e) {
			throw new InvalidPdfException(e);
		}

		if (pdfReader.getCertificationLevel() == PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED && !Boolean.parseBoolean(extraParams.getProperty("allowSigningCertifiedPdfs"))) { //$NON-NLS-1$
			// Si no permitimos dialogos graficos o directamente hemos indicado que no permitimos firmar PDF certificados lanzamos
			// una excepcion
			if (Boolean.parseBoolean(extraParams.getProperty("headLess")) || "false".equalsIgnoreCase(extraParams.getProperty("allowSigningCertifiedPdfs"))) {  //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				throw new PdfIsCertifiedException();
			}
			// En otro caso, perguntamos al usuario
			if (AOUIFactory.NO_OPTION == AOUIFactory.showConfirmDialog(
				null,
				CommonPdfMessages.getString("AOPDFSigner.8"), //$NON-NLS-1$
				CommonPdfMessages.getString("AOPDFSigner.9"), //$NON-NLS-1$
				AOUIFactory.YES_NO_OPTION,
				AOUIFactory.WARNING_MESSAGE)
			) {
				throw new AOCancelledOperationException("El usuario no ha permitido la firma de un PDF certificado"); //$NON-NLS-1$
			}
		}

		// Los derechos van firmados por Adobe, y como desde iText se invalidan
		// es mejor quitarlos
		pdfReader.removeUsageRights();

		final ByteArrayOutputStream baos = new ByteArrayOutputStream();

		// Activar el atributo de "agregar firma" (cuarto parametro del metodo
		// "PdfStamper.createSignature") hace que se cree una nueva revision del
		// documento y evita que las firmas previas queden invalidadas. Sin embargo, este
		// exige que el PDF no incorpore ningun error, asi que lo mantendremos desactivado
		// para la primera firma y activado para las subsiguientes. Un error incorporado
		// en un PDF erroneo puede quedar subsanado en su version firmada, haciendo
		// posible incorporar nuevas firmas agregando revisiones del documento.
		final PdfStamper stp;
		try {
			stp = PdfStamper.createSignature(
				pdfReader, // PDF de entrada
				baos, // Salida
				'\0', // Mantener version
				null, // No crear temporal
				pdfReader.getAcroFields().getSignatureNames().size() > 0, // Si hay mas firmas, creo una revision
				signTime
			);
		}
		catch(final BadPasswordException e) {
			throw new PdfIsPasswordProtectedException(e);
		}

		// Aplicamos todos los atributos de firma
		final PdfSignatureAppearance sap = stp.getSignatureAppearance();
		stp.setFullCompression();
		sap.setAcro6Layers(true);

		// PAdES parte 3 seccion 4.7 - Habilitacion para LTV
		stp.getWriter().addDeveloperExtension(new PdfDeveloperExtension(
			new PdfName("ESIC"), //$NON-NLS-1$
			PdfWriter.PDF_VERSION_1_7,
			1
		));

		// Adjuntos
		if (b64Attachment != null && attachmentFileName != null) {
			byte[] attachment = null;
			try {
				attachment = Base64.decode(b64Attachment);
			}
			catch(final IOException e) {
				LOGGER.warning("Se ha indicado un adjunto, pero no estaba en formato Base64, se ignorara : " + e); //$NON-NLS-1$
			}
			if (attachment != null) {
				stp.getWriter().addFileAttachment(attachmentDescription, attachment, null, attachmentFileName);
			}
		}

		// iText antiguo
		sap.setRender(PdfSignatureAppearance.SignatureRenderDescription);
		// En iText nuevo seria "sap.setRenderingMode(PdfSignatureAppearance.RenderingMode.NAME_AND_DESCRIPTION);"

		// Razon de firma
		if (reason != null) {
			sap.setReason(reason);
		}

		sap.setSignDate(signTime);

		// Gestion de los cifrados
		if (pdfReader.isEncrypted() && (ownerPassword != null || userPassword != null)) {
			if (Boolean.TRUE.toString().equalsIgnoreCase(extraParams.getProperty("avoidEncryptingSignedPdfs"))) { //$NON-NLS-1$
				LOGGER.info(
					"Aunque el PDF original estaba encriptado no se encriptara el PDF firmado (se establecio el indicativo 'avoidEncryptingSignedPdfs')" //$NON-NLS-1$
				);
			}
			else {
				LOGGER.info(
					"El PDF original estaba encriptado, se intentara encriptar tambien el PDF firmado" //$NON-NLS-1$
				);
				try {
					stp.setEncryption(
						ownerPassword != null ? ownerPassword.getBytes() : null,
						userPassword != null ? userPassword.getBytes() : null,
						pdfReader.getPermissions(),
						pdfReader.getCryptoMode()
					);
				}
				catch (final DocumentException de) {
					LOGGER.warning(
						"No se ha podido cifrar el PDF destino, se escribira sin contrasena: " + de //$NON-NLS-1$
					);
				}
			}
		}

		// Pagina en donde se imprime la firma
		if (page == LAST_PAGE) {
			page = pdfReader.getNumberOfPages();
		}

		// Posicion de la firma
		final Rectangle signaturePositionOnPage = getSignaturePositionOnPage(extraParams);
		if (signaturePositionOnPage != null && signatureField == null) {
			sap.setVisibleSignature(signaturePositionOnPage, page, null);
		}
		else if (signatureField != null) {
			sap.setVisibleSignature(signatureField);
		}

		// Localizacion en donde se produce la firma
		if (signatureProductionCity != null) {
			sap.setLocation(signatureProductionCity);
		}

		// Contacto del firmante
		if (signerContact != null) {
			sap.setContact(signerContact);
		}

		// Rubrica de la firma
		if (rubric != null) {
			sap.setImage(rubric);
			sap.setLayer2Text(""); //$NON-NLS-1$
			sap.setLayer4Text(""); //$NON-NLS-1$
		}

		// **************************
		// ** Texto en las capas ****
		// **************************

		// Capa 2
		if (layer2Text != null) {

			sap.setLayer2Text(layer2Text);

			final int layer2FontColorR;
			final int layer2FontColorG;
			final int layer2FontColorB;

			if ("black".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 0;
				layer2FontColorG = 0;
				layer2FontColorB = 0;
			}
			else if ("white".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 255;
				layer2FontColorG = 255;
				layer2FontColorB = 255;
			}
			else if ("lightGray".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 192;
				layer2FontColorG = 192;
				layer2FontColorB = 192;
			}
			else if ("gray".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 128;
				layer2FontColorG = 128;
				layer2FontColorB = 128;
			}
			else if ("darkGray".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 64;
				layer2FontColorG = 64;
				layer2FontColorB = 64;
			}
			else if ("red".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 255;
				layer2FontColorG = 0;
				layer2FontColorB = 0;
			}
			else if ("pink".equalsIgnoreCase(layer2FontColor)) { //$NON-NLS-1$
				layer2FontColorR = 255;
				layer2FontColorG = 175;
				layer2FontColorB = 175;
			}
			else if (layer2FontColor == null) {
				layer2FontColorR = 0;
				layer2FontColorG = 0;
				layer2FontColorB = 0;
			}
			else {
				LOGGER.warning("No se soporta el color '" + layer2FontColor + "' para el texto de la capa 4, se usara negro"); //$NON-NLS-1$ //$NON-NLS-2$
				layer2FontColorR = 0;
				layer2FontColorG = 0;
				layer2FontColorB = 0;
			}

			com.lowagie.text.Font font;
			try {
				Class<?> colorClass;
				if (Platform.getOS() == OS.ANDROID) {
					colorClass = Class.forName("harmony.java.awt.Color"); //$NON-NLS-1$
				}
				else {
					colorClass = Class.forName("java.awt.Color"); //$NON-NLS-1$
				}
				final Object color = colorClass.getConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE).newInstance(
					Integer.valueOf(layer2FontColorR),
					Integer.valueOf(layer2FontColorG),
					Integer.valueOf(layer2FontColorB)
				);

				font = com.lowagie.text.Font.class
					.getConstructor(Integer.TYPE, Integer.TYPE, Integer.TYPE, colorClass)
						.newInstance(
							// Family (COURIER = 0, HELVETICA = 1, TIMES_ROMAN = 2, SYMBOL = 3, ZAPFDINGBATS = 4)
							Integer.valueOf(layer2FontFamily == UNDEFINED ? COURIER : layer2FontFamily),
							// Size (DEFAULTSIZE = 12)
							Integer.valueOf(layer2FontSize == UNDEFINED ? DEFAULT_LAYER_2_FONT_SIZE : layer2FontSize),
							// Style (NORMAL = 0, BOLD = 1, ITALIC = 2, BOLDITALIC = 3, UNDERLINE = 4, STRIKETHRU = 8)
							Integer.valueOf(layer2FontStyle == UNDEFINED ? com.lowagie.text.Font.NORMAL : layer2FontStyle),
							// Color
							color
				);
			}
			catch (final Exception e) {
				font = new com.lowagie.text.Font(
					// Family (COURIER = 0, HELVETICA = 1, TIMES_ROMAN = 2, SYMBOL = 3, ZAPFDINGBATS = 4)
					layer2FontFamily == UNDEFINED ? COURIER : layer2FontFamily,
					// Size (DEFAULTSIZE = 12)
					layer2FontSize == UNDEFINED ? DEFAULT_LAYER_2_FONT_SIZE : layer2FontSize,
					// Style (NORMAL = 0, BOLD = 1, ITALIC = 2, BOLDITALIC = 3, UNDERLINE = 4, STRIKETHRU = 8)
					layer2FontStyle == UNDEFINED ? com.lowagie.text.Font.NORMAL : layer2FontStyle,
					// Color
					null
				);
			}
			sap.setLayer2Font(font);
		}

		// Capa 4
		if (layer4Text != null) {
			sap.setLayer4Text(layer4Text);
		}

		// ***************************
		// ** Fin texto en las capas *
		// ***************************

		sap.setCrypto(null, certChain, null, null);

		final PdfSignature dic = new PdfSignature(
			PdfName.ADOBE_PPKLITE,
			signatureSubFilter != null && !"".equals(signatureSubFilter) ? new PdfName(signatureSubFilter) : PdfName.ADBE_PKCS7_DETACHED //$NON-NLS-1$
		);

		// Fecha de firma
		if (sap.getSignDate() != null) {
			dic.setDate(new PdfDate(sap.getSignDate()));
		}

		dic.setName(PdfPKCS7.getSubjectFields(certChain[0]).getField("CN")); //$NON-NLS-1$

		if (sap.getReason() != null) {
			dic.setReason(sap.getReason());
		}

		// Lugar de la firma
		if (sap.getLocation() != null) {
			dic.setLocation(sap.getLocation());
		}

		// Contacto del firmante
		if (sap.getContact() != null) {
			dic.setContact(sap.getContact());
		}

		sap.setCryptoDictionary(dic);

		// Certificacion del PDF (NOT_CERTIFIED = 0, CERTIFIED_NO_CHANGES_ALLOWED = 1,
		// CERTIFIED_FORM_FILLING = 2, CERTIFIED_FORM_FILLING_AND_ANNOTATIONS = 3)
		if (certificationLevel != -1) {
			sap.setCertificationLevel(certificationLevel);
		}

		// Reservamos el espacio necesario en el PDF para insertar la firma
		final HashMap<PdfName, Integer> exc = new HashMap<PdfName, Integer>();
		exc.put(PdfName.CONTENTS, Integer.valueOf(CSIZE * 2 + 2));

		sap.preClose(exc, signTime);

		final PdfObject pdfObject = ((com.lowagie.text.pdf.PdfStamperImp) stp.getWriter()).getFileID();

		return new PdfTriPhaseSession(sap, baos, new String(pdfObject.getBytes()));
}

    private static com.lowagie.text.Image getRubricImage(final String imagebase64Encoded) {
    	if (imagebase64Encoded == null || "".equals(imagebase64Encoded)) { //$NON-NLS-1$
    		return null;
    	}
    	final byte[] image;
    	try {
			image = Base64.decode(imagebase64Encoded);
		}
    	catch (final Exception e) {
    		LOGGER.severe("Se ha proporcionado una imagen de rubrica que no esta codificada en Base64: " + e); //$NON-NLS-1$
			return null;
		}
    	try {
			return new Jpeg(image);
		}
    	catch (final Exception e) {
    		LOGGER.severe("Se ha proporcionado una imagen de rubrica que no esta codificada en JPEG: " + e); //$NON-NLS-1$
			return null;
		}
    }

    /** Devuelve la posici&oacute;n de la p&aacute;gina en donde debe agregarse
     * la firma. La medida de posicionamiento es el p&iacute;xel y se cuenta en
     * el eje horizontal de izquierda a derecha y en el vertical de abajo a
     * arriba. */
    private static Rectangle getSignaturePositionOnPage(final Properties extraParams) {
    	if (extraParams.getProperty("signaturePositionOnPageLowerLeftX") != null && //$NON-NLS-1$
    		extraParams.getProperty("signaturePositionOnPageLowerLeftY") != null && //$NON-NLS-1$
			extraParams.getProperty("signaturePositionOnPageUpperRightX") != null && //$NON-NLS-1$
			extraParams.getProperty("signaturePositionOnPageUpperRightY") != null //$NON-NLS-1$
		) {
	        try {
	            return new Rectangle(Integer.parseInt(extraParams.getProperty("signaturePositionOnPageLowerLeftX")), //$NON-NLS-1$
	                                 Integer.parseInt(extraParams.getProperty("signaturePositionOnPageLowerLeftY")), //$NON-NLS-1$
	                                 Integer.parseInt(extraParams.getProperty("signaturePositionOnPageUpperRightX")), //$NON-NLS-1$
	                                 Integer.parseInt(extraParams.getProperty("signaturePositionOnPageUpperRightY")) //$NON-NLS-1$
	            );
	        }
	        catch (final Exception e) {
	        	LOGGER.severe("Se ha indicado una posicion de firma invalida: " + e); //$NON-NLS-1$
	        }
    	}
    	return null;
    }
}
