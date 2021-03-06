package org.twak.viewTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.vecmath.Point2d;
import javax.vecmath.Point3d;
import javax.vecmath.Vector2d;
import javax.vecmath.Vector3d;

import org.twak.siteplan.jme.Jme3z;
import org.twak.tweed.gen.BlockGen;
import org.twak.tweed.gen.Pano;
import org.twak.tweed.gen.PlanesGen;
import org.twak.tweed.gen.PlanesGen.Plane;
import org.twak.utils.Line;
import org.twak.utils.Mathz;
import org.twak.utils.collections.Loop;
import org.twak.utils.collections.LoopL;
import org.twak.utils.collections.Loopable;
import org.twak.utils.collections.Loopz;
import org.twak.utils.collections.MultiMap;
import org.twak.utils.collections.Streamz;
import org.twak.utils.geom.Anglez;
import org.twak.utils.geom.LinearForm;

public class FacadeFinder {

	public List<ToProjMega> results = new ArrayList();	
	BlockGen block;
	
	public static class ToProject {
		
		public Set<Pano> toProject = new HashSet();
		public Point2d s, e;
		public double heightEstimate;
		public double maxHeight, minHeight;
		
		public ToProject( Point2d s, Point2d e, double minHeight, double maxHeight ) {
			this.s = s;
			this.e = e;
			
			this.maxHeight = maxHeight;
			this.minHeight = minHeight;
		}
	}
	
	public static class ToProjMega extends ArrayList<ToProject> {
		
		public ToProjMega( Line mega ) {
			this.megafacade = new Line (new Point2d(mega.start), new Point2d(mega.end) ); // new object for XStream
		}
		
		public Line megafacade;
	}
	
	public enum FacadeMode {
		PER_GIS, PER_MEGA, PER_CAMERA, PER_CAMERA_CROPPED;
		
		@Override
		public String toString() {
			return super.toString().toLowerCase().replaceAll( "_", " " );
		}
	}
	
	public static FacadeMode facadeMode = FacadeMode.PER_CAMERA_CROPPED;
	public static int count = 0;
	
	public FacadeFinder( LoopL<Point2d> _edges, Map<Point2d, Pano> panos, List<Point3d> meshPoints, BlockGen blockGen, PlanesGen drawnPlanes ) {

		LoopL<Point2d> edges = _edges;
		this.block = blockGen;
		
		MultiMap<Point2d, Integer> whichPoly = new MultiMap();
		{
			for (int c = 0; c < edges.size(); c++) {
				Loop<Point2d>lp = edges.get(c);
				
				for (Point2d pt : lp )
					whichPoly.put(pt, c, true);
			}
		}
		
		edges = Loopz.removeInnerEdges(edges);
		
		double TOL = 0.5;
		
		Iterator<Loop<Point2d>> eit = edges.iterator();
		
		while (eit.hasNext())
			if (Loopz.area( eit.next() ) < 0)
				eit.remove();
		
		eit = edges.iterator();
		
		// use loopz.removeAdjacentEdges?
		while (eit.hasNext()) {
			Loop<Point2d> loop = eit.next();
			Loopable<Point2d> start = loop.start, current = start;
			int size = loop.count();
			
			boolean again;
			do {
				again = false;
				Point2d a = current.getPrev().get(),
						b = current.get(),
						c = current.getNext().get();
				
				Line ab = new Line(a,b),
				     bc = new Line (b,c);
				
				double angle = Anglez.dist( ab.aTan2(), bc.aTan2() );
				
				if ( 
						whichPoly.get(b).size() == 1 && (
						a.distanceSquared(b) < 0.0001 ||
						b.distanceSquared(c) < 0.0001 ||
						angle < 0.2 && Math.abs ( Mathz.area(a, b, c) ) < 50 * TOL * TOL  ) )
				{
					current.getPrev().setNext(current.getNext());
					current.getNext().setPrev(current.getPrev());
					size--;
					if (start == current)
						loop.start = start = current.getPrev();
					
					again = true;
					current = current.getPrev();
				}
				else
					current = current.getNext();
			}
			while ( ( again || current != start) && size > 2);
			
			if (size <= 2)
				eit.remove();
		}
				
		List<F> fs = new ArrayList<>();
		
		List<Loopable<Point2d>> longestFirst = edges.stream().flatMap( x -> Streamz.stream( x.loopableIterator() ) ).sorted( 
				(b,a) -> Double.compare( a.get().distanceSquared( a.getNext().get() ), b.get()
						.distanceSquared( b.getNext().get() ) ) ).collect( Collectors.toList() );
		
		for ( Loopable<Point2d> lp : longestFirst ) {

			Point2d s = lp.get(), e = lp.getNext().get();

//			Loop<Point2d> orig = _edges.get( findOrigPoly( whichPoly, s, e ) );

			Line l = new SuperLine( s, e );

			boolean found = false;

			f: for ( F f : fs )
				if ( f.matches( l ) ) {
					found = true;
					break f;
				}

			if ( !found )
				fs.add( new F( l ) );
		}
		
		for (F f : fs) {
			
			if (f.length < 2)
				continue;
			
			Line drawnPlane = null;
			if (drawnPlanes != null) {
				
				Line outl = f.getExtent();
				
				Optional<Plane> op = drawnPlanes.planes.stream().filter( p -> {
					Line pl = new Line ( Jme3z.to2 ( p.a ), Jme3z.to2 ( p.b  ) );
					double angle = pl.absAngle( outl );
					return pl.distance( outl ) < 5 && (angle < 0.3 || angle > Math.PI - 0.3); 
				}).findAny();
				
				if (op.isPresent()) {
					drawnPlane = new Line ( Jme3z.to2( op.get().a ), Jme3z.to2( op.get().b ) );
				}
				
				if (drawnPlane == null)
					continue;
			}
			
			ToProjMega megaResults = new ToProjMega(f.getExtent().reverse());
			
			results.add( megaResults );
			
			if ( facadeMode == FacadeMode.PER_GIS ) {

				for (Line l : f.facades) {

					if ( l.lengthSquared() < 1 )
						continue;

					double height;
					
					if ( l instanceof SuperLine ) {
						height = 30;
					} else { //heights from mesh
						height = guessHeight( meshPoints, l );
					}

					System.out.println( "estimate facade height as " + height );

					Line ex = new Line( l );
					ex = ex.reverse();
					Vector2d dir = ex.dir();

					dir.scale( 10 / dir.length() );

					ex.start.sub( dir );
					ex.end.add( dir );

					ToProject out = new ToProject( ex.start, ex.end, 0, height );

					double closestDist = 20;
					Point2d closestP = null;
					
					Point2d mid = ex.fromPPram( 0.5 );
					
					for ( Point2d p : panos.keySet() ) {

						if ( ex.isOnLeft( p ) )
//							if ( GMLGen.mode == Mode.RENDER_SELECTED_FACADE ) {

								if ( ex.distance( p, true ) < 20 )
									out.toProject.add( panos.get( p ) );
//							} else {
//
//								double dist = mid.distance( p );
//								if ( dist < closestDist ) {
//									closestDist = dist;
//									closestP = p;
//								}
//							}
					}
					
//					if ( GMLGen.mode == Mode.RENDER_SELECTED_FACADE ) {
						if ( out.toProject.size() > 0 )
							megaResults.add( out );
//					} else {
//
//						if ( closestP != null ) {
//							out.toProject.add( panos.get( closestP ) );
//							megaResults.add( out );
//						}
//					}
				}
			} else if (facadeMode == FacadeMode.PER_MEGA ){
				
				Line mega = f.getExtent();

				
				Vector2d dir = mega.dir();
				dir.scale (7 / dir.length());

				Point2d s = new Point2d (mega.start), e = new Point2d(mega.end);
				e.add(dir);
				s.sub(dir);
				
				for ( Point2d p : panos.keySet() ) {
					
					Point2d cen = mega.project( p, false );
					
					if ( mega.isOnLeft( p ) && mega.distance( p, true ) < 20 &&
						Mathz.inRange( mega.findPPram( cen ), 0, 1 ) ) {

							double height = panos.get(p).location.y;
							ToProject out = new ToProject( s, e, 0, height + 30 );
							megaResults.add( out );

							out.toProject.add( panos.get( p ) );
						}
					}
				
			} else if (facadeMode == FacadeMode.PER_CAMERA || facadeMode == FacadeMode.PER_CAMERA_CROPPED ){
				
				Line l = f.getExtent();
				double lLength = l.length();
				
				for ( Point2d p : panos.keySet() ) {
					
					if ( l.isOnLeft( p ) && l.distance( p, true ) < 20 ) {
						
						double fovX2 = Math.PI/3;
						
						Point2d cen = l.project( p, false );
						
						
						double dist = cen.distance( p ), frac = l.findPPram( cen );
						
						if (dist > 20 || !Mathz.inRange( frac * lLength, -2, lLength + 2) )
							continue;
						
						double xLen = dist * Math.tan(fovX2);
						
						Vector2d dirX = l.dir();
						dirX.normalize();
						
						Point2d left = new Point2d(dirX);
						
						left.scaleAdd(xLen, cen);
						
						Point2d right = new Point2d(dirX);
						right.scaleAdd(-xLen, cen);
						
						if ( facadeMode == FacadeMode.PER_CAMERA_CROPPED ) {
							
							double beyond = 6;
							
							double min = -beyond / l.length(), max = 1 + ( beyond / l.length() );
							
							if (drawnPlane != null) {

								double s = l.findPPram( drawnPlane.start ),
									   e = l.findPPram( drawnPlane.end   );
								
								if (s > e) {
									double tmp = e;
									e = s;
									s = tmp;
								}
								
								min = Mathz.clamp( min, s, e );
								max = Mathz.clamp( max, s, e );
							}
							
							if ( l.findPPram( right ) < min )
								right = l.fromPPram( min );
							if ( l.findPPram( left ) > max )
								left = l.fromPPram( max );
						}
						
						
						double height = panos.get(p).location.y;
						
						ToProject out = new ToProject( right, left, 0, height + 30 );
						
						if ( left.distance( right ) > 3 ) {
							out.toProject.add( panos.get( p ) );
							megaResults.add( out );
							count ++;
						}
					}
				}
			}
			
			
			Collections.sort( megaResults, new Comparator<ToProject>() {
				
				@Override
				public int compare( ToProject o1, ToProject o2 ) {
					return Double.compare( pram(o1), pram(o2) );
				}
				
				private double pram( ToProject o1 ) {
					Vector3d pt = o1.toProject.iterator().next().location;
					return megaResults.megafacade.findPPram( new Point2d (pt.x, pt.z) );
				}
			} );
		}
	}

	private double guessHeight( List<Point3d> meshPoints, Line l ) {
		double height;
		height = 2;
		
//		Line shortL = new Line (l.fromFrac( 0.1 ), l.fromFrac(0.9));
		
		for ( Point3d pt : meshPoints ) {
			if ( adjacentDist( l, new Point2d( pt.x, pt.z ) ) < 2 )
				height = Math.max( height, pt.y );
		}
		return height;
	}

//	private int findOrigPoly( MultiMap<Point2d, Integer> whichPoly, Point2d s, Point2d e ) {
//		
//		List<Integer > js = whichPoly.get( e );
//		for (Integer i : whichPoly.get( s ) ) {
//			for (Integer j : js) {
//				if (i == j)
//					return i;
//			}
//		}
//		
//		return -1;
//	}

	private static class F {
		
		LinearForm lf;
		List<Line> facades = new ArrayList();
		double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
		double length;
		
		public F( Line l ) {
			lf = new LinearForm( l );
			matches(l);
		}
		
		public boolean matches(Line f) {
			if ( lf.angle( new LinearForm(f) ) < 0.1 &&
					lf.distance( f.start ) < 3 ) {
				
				for ( Point2d pt : f.points() ) {
					double p = lf.findPParam( pt );

					min = Math.min( min, p );
					max = Math.max( max, p );
				}
				
				facades.add(f);
				length += f.length();
				
				return true;
			}
			return false;
		}
		
		public Line getExtent() {
			return new Line(lf.fromPParam( min ), lf.fromPParam( max ));
		}
	}
	
	public double adjacentDist(Line l, Point2d pt) {

		Vector2d v1 = new Vector2d(l.end);
		v1.sub(l.start);
		Vector2d v2 = new Vector2d(pt);
		v2.sub(l.start);
		double param = v2.dot(v1) / v1.length();

		if ( param < 0 || param > v1.length() )
			return Double.MAX_VALUE;
		
		v1.normalize();
		v1.scale( param );
		v1.add( l.start );
		
		return new Point2d (v1).distance(pt);
	}

}
