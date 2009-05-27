package freenet.clients.http;

import freenet.l10n.L10n;
import java.io.IOException;
import java.net.URI;

import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

/**
 * Toadlet for "Freenet is starting up" page.
 */
public class StartupToadlet extends Toadlet {

	private StaticToadlet staticToadlet;
	private volatile boolean isPRNGReady = false;

	public StartupToadlet(StaticToadlet staticToadlet) {
		super(null);
		this.staticToadlet = staticToadlet;
	}

	@Override
	public void handleGet(URI uri, HTTPRequest req, ToadletContext ctx) throws ToadletContextClosedException, IOException, RedirectException {
		// If we don't disconnect we will have pipelining issues
		ctx.forceDisconnect();

		String path = uri.getPath();
		if(path.startsWith(StaticToadlet.ROOT_URL) && staticToadlet != null)
			staticToadlet.handleGet(uri, req, ctx);
		else {
			String desc = L10n.getString("StartupToadlet.title");
			PageNode page = ctx.getPageMaker().getPageNode(desc, false, ctx);
			HTMLNode pageNode = page.outer;
			HTMLNode headNode = page.headNode;
			headNode.addChild("meta", new String[]{"http-equiv", "content"}, new String[]{"refresh", "20; url="});
			HTMLNode contentNode = page.content;

			if(!isPRNGReady) {
				HTMLNode prngInfoboxContent = ctx.getPageMaker().getInfobox("infobox-error", L10n.getString("StartupToadlet.entropyErrorTitle"), contentNode);
				prngInfoboxContent.addChild("#", L10n.getString("StartupToadlet.entropyErrorContent"));
			}

			HTMLNode infoboxContent = ctx.getPageMaker().getInfobox("infobox-error", desc, contentNode);
			infoboxContent.addChild("#", L10n.getString("StartupToadlet.isStartingUp"));

			WelcomeToadlet.maybeDisplayWrapperLogfile(ctx, contentNode);

			//TODO: send a Retry-After header ?
			writeHTMLReply(ctx, 503, desc, pageNode.generate());
		}
	}

	@Override
	public String supportedMethods() {
		return "GET";
	}

	public void setIsPRNGReady() {
		isPRNGReady = true;
	}
}
