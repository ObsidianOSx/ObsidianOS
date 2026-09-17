-- Lets signed-in users of this server check whether a username exists, so Obsidian Chat can say
-- "no one by that name" instead of waiting forever. Standard XMPP answers the same either way to
-- stop anyone probing for accounts; this answers only local, authenticated sessions, and only a
-- limited number of times per user.
--
-- Request:  <iq type='get' to='<host>'><exists xmlns='urn:obsidian:lookup:0' user='alice'/></iq>
-- Reply:    empty result if the account exists, item-not-found if it doesn't.
local st = require "util.stanza";
local nodeprep = require "util.encodings".stringprep.nodeprep;
local usermanager = require "core.usermanager";

local xmlns = "urn:obsidian:lookup:0";
local max_lookups = module:get_option_number("obsidian_lookup_limit", 30);
local window = module:get_option_number("obsidian_lookup_window", 600);
local recent = {};

module:add_feature(xmlns);

module:hook("iq-get/host/"..xmlns..":exists", function(event)
	local origin, stanza = event.origin, event.stanza;
	if origin.type ~= "c2s" or not origin.username or origin.host ~= module.host then
		origin.send(st.error_reply(stanza, "auth", "forbidden"));
		return true;
	end

	local now = os.time();
	local count = recent[origin.username];
	if not count or now - count.since > window then
		count = { since = now, n = 0 };
		recent[origin.username] = count;
	end
	count.n = count.n + 1;
	if count.n > max_lookups then
		origin.send(st.error_reply(stanza, "wait", "resource-constraint", "Too many lookups, try again later"));
		return true;
	end

	local user = nodeprep(stanza.tags[1].attr.user or "");
	if user and user ~= "" and usermanager.user_exists(user, module.host) then
		origin.send(st.reply(stanza));
	else
		origin.send(st.error_reply(stanza, "cancel", "item-not-found"));
	end
	return true;
end);
