package com.flatironschool.javacs;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import org.jsoup.select.Elements;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

/**
 * Represents a Redis-backed web search index.
 *
 */
public class JedisIndex {

	private Jedis jedis;

	/**
	 * Constructor.
	 *
	 * @param jedis
	 */
	public JedisIndex(Jedis jedis) {
		this.jedis = jedis;
	}

	/**
	 * Returns the Redis key for a given search term.
	 *
	 * @return Redis key.
	 */
	private String urlSetKey(String term) {
		return "URLSet:" + term;
	}

	/**
	 * Returns the Redis key for a URL's TermCounter.
	 *
	 * @return Redis key.
	 */
	private String termCounterKey(String url) {
		return "TermCounter:" + url;
	}

	/**
	 * Checks whether we have a TermCounter for a given URL.
	 *
	 * @param url
	 * @return
	 */
	public boolean isIndexed(String url) {
		String redisKey = termCounterKey(url);
		return jedis.exists(redisKey);
	}

	/**
	 * Looks up a search term and returns a set of URLs.
	 *
	 * @param term
	 * @return Set of URLs.
	 */
	public Set<String> getURLs(String term) {
        // Attain the set created on the Redis database for the given term
		return this.jedis.smembers(this.urlSetKey(term));
	}

    /**
	 * Looks up a term and returns a map from URL to count.
	 *
	 * @param term
	 * @return Map from URL to count.
	 */
	public Map<String, Integer> getCounts(String term) {
		Map<String, Integer> urlToCounts = new HashMap<String, Integer>();

        Set<String> urlsContainingTerm = this.getURLs(term);
		for (String url : urlsContainingTerm) {
			//Populate the HashMap
			urlToCounts.put(url, this.getCount(url, term));
		}

		return urlToCounts;
	}

    /**
	 * Returns the number of times the given term appears at the given URL.
	 *
	 * @param url
	 * @param term
	 * @return
	 */
	public Integer getCount(String url, String term) {
        String redisTermCounterKey = this.termCounterKey(url);
		return new Integer(this.jedis.hget(redisTermCounterKey, term));
	}


	/**
	 * Add a page to the index.
	 *
	 * @param url         URL of the page.
	 * @param paragraphs  Collection of elements that should be indexed.
	 */
	public void indexPage(String url, Elements paragraphs) {
		//Create the Jedis key for the Hash that maps every term to the amount of times it appears on a page
        String redisTermCounterKey = this.termCounterKey(url);
		//Create term counter and log term counts
		TermCounter counter = new TermCounter(url);
		counter.processElements(paragraphs);
		counter.printCounts();

		//Process commands faster with a Transaction
		Transaction transaction = this.jedis.multi();
		//Store the term counter results in the Redis database
		for (String term : counter.keySet()) {
			transaction.hset(redisTermCounterKey, term, Integer.toString(counter.get(term)));
			//System.out.println(" -----> " + term + " is currently being uploaded with a count of: " + counter.get(term));

			//Build the Redis set of URLs for each term
			String redisUrlSetKey = this.urlSetKey(term);
			transaction.sadd(redisUrlSetKey, url);
		}
		//Complete all stored jedis actions
		transaction.exec();
	}

	/**
	 * Create a term counter
	 *
	 */

	/**
	 * Prints the contents of the index.
	 *
	 * Should be used for development and testing, not production.
	 */
	public void printIndex() {
		// loop through the search terms
		for (String term: termSet()) {
			System.out.println(term);

			// for each term, print the pages where it appears
			Set<String> urls = getURLs(term);
			for (String url: urls) {
				Integer count = getCount(url, term);
				System.out.println("    " + url + " " + count);
			}
		}
	}

	/**
	 * Returns the set of terms that have been indexed.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public Set<String> termSet() {
		Set<String> keys = urlSetKeys();
		Set<String> terms = new HashSet<String>();
		for (String key: keys) {
			String[] array = key.split(":");
			if (array.length < 2) {
				terms.add("");
			} else {
				terms.add(array[1]);
			}
		}
		return terms;
	}

	/**
	 * Returns URLSet keys for the terms that have been indexed.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public Set<String> urlSetKeys() {
		return jedis.keys("URLSet:*");
	}

	/**
	 * Returns TermCounter keys for the URLS that have been indexed.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public Set<String> termCounterKeys() {
		return jedis.keys("TermCounter:*");
	}

	/**
	 * Deletes all URLSet objects from the database.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public void deleteURLSets() {
		Set<String> keys = urlSetKeys();
		Transaction t = jedis.multi();
		for (String key: keys) {
			t.del(key);
		}
		t.exec();
	}

	/**
	 * Deletes all URLSet objects from the database.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public void deleteTermCounters() {
		Set<String> keys = termCounterKeys();
		Transaction t = jedis.multi();
		for (String key: keys) {
			t.del(key);
		}
		t.exec();
	}

	/**
	 * Deletes all keys from the database.
	 *
	 * Should be used for development and testing, not production.
	 *
	 * @return
	 */
	public void deleteAllKeys() {
		Set<String> keys = jedis.keys("*");
		Transaction t = jedis.multi();
		for (String key: keys) {
			t.del(key);
		}
		t.exec();
	}

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(String[] args) throws IOException {
		Jedis jedis = JedisMaker.make();
		JedisIndex index = new JedisIndex(jedis);

		//index.deleteTermCounters();
		//index.deleteURLSets();
		//index.deleteAllKeys();
		loadIndex(index);


		Map<String, Integer> map = index.getCounts("the");
		for (Entry<String, Integer> entry: map.entrySet()) {
			System.out.println(entry);
		}

		//Testing
		/*
		String url = "https://en.wikipedia.org/wiki/Java_(programming_language)";
		String word = "the";
		System.out.println("Frequency of " + word + " is "+ index.getCount(url, word));
		System.out.println("Check: " + url + "is a member " + index.jedis.sismember(index.urlSetKey(word), url));

		System.out.println(index.getCounts(word));
		*/
	}

	/**
	 * Stores two pages in the index for testing purposes.
	 *
	 * @return
	 * @throws IOException
	 */
	private static void loadIndex(JedisIndex index) throws IOException {
		WikiFetcher wf = new WikiFetcher();

		String url = "https://en.wikipedia.org/wiki/Java_(programming_language)";
		Elements paragraphs = wf.readWikipedia(url);
		index.indexPage(url, paragraphs);

		/*
		url = "https://en.wikipedia.org/wiki/Programming_language";
		paragraphs = wf.readWikipedia(url);
		index.indexPage(url, paragraphs);*/
	}
}
