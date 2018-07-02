import javax.swing._
import java.awt.event._
import java.awt.GridLayout

import scala.concurrent.duration._
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global



type Description = String
def showCheckboxWindow[T](options: Vector[(Description, T)]): Vector[(T, Boolean)] = {

  val checkboxValues: Array[Boolean] = Array.fill(options.length)(false)//options.map(_ => false).toArray

  val closedButtonPromise = Promise[Boolean]()

  val checkPanel = new JPanel(new GridLayout(0, 1))
  val frame = new JFrame("Select commits for the changelog")
  frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)
  options.zipWithIndex.foreach { case ((description, _), index) =>
    val checkbox = new JCheckBox(description)
    checkbox.addActionListener((e: ActionEvent) => {
      checkboxValues(index) = !checkboxValues(index) //toggle
    })
    checkPanel.add(checkbox)
  }
  val closeButton = new JButton("Select commits")
  closeButton.addActionListener((e: ActionEvent) => {
    closedButtonPromise.trySuccess(true)
    frame.dispose()
  })
  checkPanel.add(closeButton)

  frame.getContentPane.add(checkPanel)
  frame.pack()
  frame.setLocationRelativeTo(null);
  frame.setVisible(true)

  Await.result(closedButtonPromise.future, Duration.Inf)

  options.map(_._2).zip(checkboxValues)

}

def showSpinner[T](message: String)(code: => T): T = {

  val symbols = """|/-\"""

  val spinningCursorIterator = Iterator.from(0).map(_ % 4).map(n => symbols(n))

  val f = Future {

    code
  }

  Console.out.print(message + "... ")
  while (!f.isCompleted) {
    Console.out.print(spinningCursorIterator.next())
    Console.out.flush()
    Thread.sleep(50)
    Console.out.print('\b')
    Console.out.flush()
  }
  Await.result(f, Duration.Inf)
}