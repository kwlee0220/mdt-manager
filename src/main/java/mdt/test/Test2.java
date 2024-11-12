package mdt.test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 *
 * @author Kang-Woo Lee (ETRI)
 */
public class Test2 {
	public static void main(String... args) throws Exception {
		ScheduledExecutorService execSvc = Executors.newSingleThreadScheduledExecutor();
		Runnable work = new Runnable() {
			@Override
			public void run() {
				System.out.println("Hello");
			}
		};
		ScheduledFuture<?> schedule = execSvc.scheduleAtFixedRate(work, 0, 5, TimeUnit.SECONDS);
		try {
			schedule.get(30, TimeUnit.SECONDS);
		}
		catch ( TimeoutException e ) {
			System.out.println("timedout!!");
		}
		schedule.cancel(true);
		execSvc.shutdownNow();
	}
}
