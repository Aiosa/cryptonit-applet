import com.licel.jcardsim.base.Simulator;
import java.math.BigInteger;
import javacard.framework.AID;
import javacard.framework.Util;
import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.cryptonit.CryptonitApplet;

/**
 * @author Mathias Brossard
 */
class piv {
    private static String toHex(String prefix, byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        for (int i = 0; i < bytes.length; i++) {
            sb.append(String.format("%02x ", bytes[i]));
        }
        return sb.toString();
    }

    private static ResponseAPDU sendAPDU(Simulator simulator, CommandAPDU command) {
        ResponseAPDU response;
        System.out.println(toHex(" > ", command.getBytes()));
        response = new ResponseAPDU(simulator.transmitCommand(command.getBytes()));
        System.out.println(toHex(" < ", response.getData())
                + String.format("[sw=%04X l=%d]", response.getSW(), response.getData().length));
        return response;
    }

    public static void main(String[] args) {
        ResponseAPDU response;
        Simulator simulator = new Simulator();
        byte[] arg;
        byte[] appletAIDBytes = new byte[]{
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x03,
            (byte) 0x08, (byte) 0x00, (byte) 0x00, (byte) 0x10,
            (byte) 0x00
        };
        short sw, le;
        AID appletAID = new AID(appletAIDBytes, (short) 0, (byte) appletAIDBytes.length);

        simulator.installApplet(appletAID, CryptonitApplet.class);
        System.out.println("Select Applet");
        response = sendAPDU(simulator, new CommandAPDU(0x00, 0xA4, 0x04, 0x00, new byte[]{
            (byte) 0xA0, (byte) 0x00, (byte) 0x00, (byte) 0x03, (byte) 0x08
        }));

        System.out.println("Management key authentication (part 1)");
        response = sendAPDU(simulator, new CommandAPDU(0x00, 0x87, 0x03, 0x9B, new byte[]{
            (byte) 0x7C, (byte) 0x02, (byte) 0x80, (byte) 0x00
        }));

        arg = new byte[]{
            (byte) 0x7C, (byte) 0x14,
            (byte) 0x80, (byte) 0x08,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
            (byte) 0x81, (byte) 0x08,
            (byte) 0x2B, (byte) 0x65, (byte) 0x4B, (byte) 0x22, (byte) 0xB2, (byte) 0x2D, (byte) 0x99, (byte) 0x7F
        };
        SecretKey key = new SecretKeySpec(new byte[]{
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        }, "DESede");
        try {
            Cipher cipher = Cipher.getInstance("DESede/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            cipher.doFinal(response.getData(), 4, 8, arg, 4);
        } catch (Exception ex) {
            ex.printStackTrace(System.out);
        }

        System.out.println("Management key authentication (part 2)");
        response = sendAPDU(simulator, new CommandAPDU(0x00, 0x87, 0x03, 0x9B, arg));
        System.out.println("Generate RSA key (9A)");
        response = sendAPDU(simulator, new CommandAPDU(0x00, 0x47, 0x00, 0x9A, new byte[]{
            (byte) 0xAC, (byte) 0x03, (byte) 0x80, (byte) 0x01, (byte) 0x07
        }));
        arg = response.getData();
        if (arg.length < 9 || arg[7] != 0x1 || arg[8] != 0x0) {
            System.err.println("Error modulus");
            return;
        }

        byte[] n = new byte[257];
        byte[] e = new byte[3];
        short s = (short) (arg.length - 9);
        Util.arrayCopy(arg, (short) 9, n, (short) 1, s);

        sw = (short) response.getSW();
        le = (short) (sw & 0xFF);
        System.out.println("Call GET RESPONSE");
        response = sendAPDU(simulator, new CommandAPDU(0x00, 0xC0, 0x00, 0x00, new byte[]{}, le));

        arg = response.getData();
        if(arg.length < (256 - s)) {
            System.err.println("Error remaining modulus");
            return;            
        }
        Util.arrayCopy(arg, (short) 0, n, (short) (s + 1), (short) (256 - s));

        s = (short) (256 - s);
        if (arg[s] != (byte) 0x82 || arg[s + 1] != (byte) 0x3) {
            System.err.println("Error exponent");
            return;
        }
        Util.arrayCopy(arg, (short) (s + 2), e, (short) 0, (short) 3);

        try {
            RSAPublicKey rsa_pub = new RSAPublicKey(new BigInteger(n), new BigInteger(e));
        } catch (Exception ex) {
            ex.printStackTrace(System.err);
            return;
        }
    }
}
